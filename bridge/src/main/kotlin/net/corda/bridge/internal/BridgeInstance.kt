package net.corda.bridge.internal

import net.corda.bridge.BridgeVersionInfo
import net.corda.bridge.services.api.*
import net.corda.bridge.services.audit.LoggingBridgeAuditService
import net.corda.bridge.services.supervisors.BridgeSupervisorServiceImpl
import net.corda.bridge.services.supervisors.FloatSupervisorServiceImpl
import net.corda.bridge.services.util.ServiceStateCombiner
import net.corda.bridge.services.util.ServiceStateHelper
import net.corda.core.concurrent.CordaFuture
import net.corda.core.internal.concurrent.openFuture
import net.corda.core.internal.div
import net.corda.core.internal.exists
import net.corda.core.internal.readObject
import net.corda.core.node.NetworkParameters
import net.corda.core.serialization.internal.SerializationEnvironmentImpl
import net.corda.core.serialization.internal.effectiveSerializationEnv
import net.corda.core.serialization.internal.nodeSerializationEnv
import net.corda.core.utilities.contextLogger
import net.corda.nodeapi.internal.ShutdownHook
import net.corda.nodeapi.internal.addShutdownHook
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.network.NETWORK_PARAMS_FILE_NAME
import net.corda.nodeapi.internal.network.SignedNetworkParameters
import net.corda.nodeapi.internal.network.verifiedNetworkMapCert
import net.corda.nodeapi.internal.serialization.AMQP_P2P_CONTEXT
import net.corda.nodeapi.internal.serialization.AMQP_STORAGE_CONTEXT
import net.corda.nodeapi.internal.serialization.SerializationFactoryImpl
import net.corda.nodeapi.internal.serialization.amqp.AMQPServerSerializationScheme
import rx.Subscription
import java.util.concurrent.atomic.AtomicBoolean

class BridgeInstance(val conf: BridgeConfiguration,
                     val versionInfo: BridgeVersionInfo,
                     private val stateHelper: ServiceStateHelper = ServiceStateHelper(log)) : ServiceLifecycleSupport, ServiceStateSupport by stateHelper {
    companion object {
        val log = contextLogger()
    }

    private val shutdown = AtomicBoolean(false)
    private var shutdownHook: ShutdownHook? = null

    private lateinit var networkParameters: NetworkParameters
    private lateinit var bridgeAuditService: BridgeAuditService
    private var bridgeSupervisorService: BridgeSupervisorService? = null
    private var floatSupervisorService: FloatSupervisorService? = null
    private var statusFollower: ServiceStateCombiner? = null
    private var statusSubscriber: Subscription? = null


    init {
        initialiseSerialization()
    }

    private fun initialiseSerialization() {
        val serializationExists = try {
            effectiveSerializationEnv
            true
        } catch (e: IllegalStateException) {
            false
        }
        if (!serializationExists) {
            val classloader = this.javaClass.classLoader
            nodeSerializationEnv = SerializationEnvironmentImpl(
                    SerializationFactoryImpl().apply {
                        registerScheme(AMQPServerSerializationScheme(emptyList()))
                    },
                    p2pContext = AMQP_P2P_CONTEXT.withClassLoader(classloader),
                    rpcServerContext = AMQP_P2P_CONTEXT.withClassLoader(classloader),
                    storageContext = AMQP_STORAGE_CONTEXT.withClassLoader(classloader),
                    checkpointContext = AMQP_P2P_CONTEXT.withClassLoader(classloader))
        }
    }

    override fun start() {
        val wasRunning = shutdown.getAndSet(true)
        require(!wasRunning) { "Already running" }
        shutdownHook = addShutdownHook {
            stop()
        }
        retrieveNetworkParameters()
        createServices()
        startServices()
    }

    override fun stop() {
        val wasRunning = shutdown.getAndSet(false)
        if (!wasRunning) {
            return
        }
        shutdownHook?.cancel()
        shutdownHook = null
        log.info("Shutting down ...")

        stopServices()

        _exitFuture.set(this)
        log.info("Shutdown complete")
    }

    private val _exitFuture = openFuture<BridgeInstance>()
    val onExit: CordaFuture<BridgeInstance> get() = _exitFuture

    private fun retrieveNetworkParameters() {
        val trustRoot = conf.loadTrustStore().getCertificate(X509Utilities.CORDA_ROOT_CA)
        val networkParamsFile = conf.baseDirectory / NETWORK_PARAMS_FILE_NAME
        require(networkParamsFile.exists()) { "No network-parameters file found." }
        networkParameters = networkParamsFile.readObject<SignedNetworkParameters>().verifiedNetworkMapCert(trustRoot)
        log.info("Loaded network parameters: $networkParameters")
        check(networkParameters.minimumPlatformVersion <= versionInfo.platformVersion) {
            "Node's platform version is lower than network's required minimumPlatformVersion"
        }
    }

    private fun createServices() {
        bridgeAuditService = LoggingBridgeAuditService(conf)
        when (conf.bridgeMode) {
        // In the SenderReceiver mode the inbound and outbound message paths are run from within a single bridge process.
        // The process thus contains components that listen for bridge control messages on Artemis.
        // The process can then initiates TLS/AMQP 1.0 connections to remote peers and transfers the outbound messages.
        // The process also runs a TLS/AMQP 1.0 server socket, which is can receive connections and messages from peers,
        // validate the messages and then forwards the packets to the Artemis inbox queue of the node.
            BridgeMode.SenderReceiver -> {
                floatSupervisorService = FloatSupervisorServiceImpl(conf, networkParameters.maxMessageSize, bridgeAuditService)
                bridgeSupervisorService = BridgeSupervisorServiceImpl(conf, networkParameters.maxMessageSize, bridgeAuditService, floatSupervisorService!!.amqpListenerService)
            }
        // In the FloatInner mode the process runs the full outbound message path as in the SenderReceiver mode, but the inbound path is split.
        // This 'Float Inner/Bridge Controller' process runs the more trusted portion of the inbound path.
        // In particular the 'Float Inner/Bridge Controller' has access to the persisted TLS KeyStore, which it provisions dynamically into the 'Float Outer'.
        // Also the the 'Float Inner' does more complete validation of inbound messages and ensures that they correspond to legitimate
        // node inboxes, before transferring the message to Artemis. Potentially it might carry out deeper checks of received packets.
        // However, the 'Float Inner' is not directly exposed to the internet, or peers and does not host the TLS/AMQP 1.0 server socket.
            BridgeMode.FloatInner -> {
                bridgeSupervisorService = BridgeSupervisorServiceImpl(conf, networkParameters.maxMessageSize, bridgeAuditService, null)
            }
        // In the FloatOuter mode this process runs a minimal AMQP proxy that is designed to run in a DMZ zone.
        // The process holds the minimum data necessary to act as the TLS/AMQP 1.0 receiver socket and tries
        // to minimise any state. It specifically does not persist the Node TLS keys anywhere, nor does it hold network map information on peers.
        // The 'Float Outer' does not initiate socket connection anywhere, so that attackers can be easily blocked by firewalls
        // if they try to invade the system from a compromised 'Float Outer' machine. The 'Float Outer' hosts a control TLS/AMQP 1.0 server socket,
        // which receives a connection from the 'Float Inner/Bridge controller' in the trusted zone of the organisation.
        // The control channel is ideally authenticated using server/client certificates that are not related to the Corda PKI hierarchy.
        // Once the control channel is formed it is used to RPC the methods of the BridgeAMQPListenerService to start the publicly visible
        // TLS/AMQP 1.0 server socket of the Corda node. Thus peer connections will directly terminate onto the activate listener socket and
        // be validated against the keys/certificates sent across the control tunnel. Inbound messages are given basic checks that do not require
        // holding potentially sensitive information and are then forwarded across the control tunnel to the 'Float Inner' process for more
        // complete validation checks.
            BridgeMode.FloatOuter -> {
                floatSupervisorService = FloatSupervisorServiceImpl(conf, networkParameters.maxMessageSize, bridgeAuditService)
            }
        }
        statusFollower = ServiceStateCombiner(listOf(bridgeAuditService, floatSupervisorService, bridgeSupervisorService).filterNotNull())
        statusSubscriber = statusFollower!!.activeChange.subscribe {
            stateHelper.active = it
        }
    }

    private fun startServices() {
        bridgeAuditService.start()
        bridgeSupervisorService?.start()
        floatSupervisorService?.start()
    }

    private fun stopServices() {
        stateHelper.active = false
        floatSupervisorService?.stop()
        bridgeSupervisorService?.stop()
        bridgeAuditService.stop()
        statusSubscriber?.unsubscribe()
        statusSubscriber = null
        statusFollower = null
    }
}