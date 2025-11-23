package com.example.measureapp.ar

import com.google.ar.core.Anchor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages AR anchors for persistent tracking of measurement points
 */
@Singleton
class AnchorManager @Inject constructor() {
    
    private val anchors = mutableListOf<Anchor>()
    
    /**
     * Add an anchor to be tracked
     */
    fun addAnchor(anchor: Anchor) {
        anchors.add(anchor)
    }
    
    /**
     * Remove an anchor and detach it from the session
     */
    fun removeAnchor(anchor: Anchor) {
        anchors.remove(anchor)
        anchor.detach()
    }
    
    /**
     * Get all active anchors
     */
    fun getAllAnchors(): List<Anchor> {
        return anchors.toList()
    }
    
    /**
     * Clear all anchors and detach them
     */
    fun clearAll() {
        anchors.forEach { it.detach() }
        anchors.clear()
    }
    
    /**
     * Remove last anchor (for undo functionality)
     */
    fun removeLast(): Anchor? {
        return if (anchors.isNotEmpty()) {
            val last = anchors.removeAt(anchors.lastIndex)
            last.detach()
            last
        } else {
            null
        }
    }
    
    /**
     * Get anchor count
     */
    fun getCount(): Int = anchors.size
}
