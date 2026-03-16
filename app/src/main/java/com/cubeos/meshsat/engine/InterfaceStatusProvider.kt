package com.cubeos.meshsat.engine

/**
 * Reports whether an interface is online.
 * Implemented by GatewayService based on transport connection state.
 * Phase C (MESHSAT-61) will add a full InterfaceManager state machine;
 * this is the minimal contract needed for failover resolution now.
 */
fun interface InterfaceStatusProvider {
    fun isOnline(interfaceId: String): Boolean
}
