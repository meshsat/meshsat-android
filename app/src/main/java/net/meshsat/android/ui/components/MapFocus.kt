package net.meshsat.android.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A request to centre the map on one mesh node (People, "Show on map", MESHSAT-1249). The map
 * lives outside the NavHost and takes no route argument, so the caller sets the node here and
 * switches to the Map tab; the map centres on the node's latest position and calls [consumed].
 */
object MapFocus {
    private val _node = MutableStateFlow<Long?>(null)

    /** The node number to centre on, or null when nothing is asked. */
    val node: StateFlow<Long?> = _node

    fun show(nodeNum: Long) {
        _node.value = nodeNum
    }

    fun consumed() {
        _node.value = null
    }
}
