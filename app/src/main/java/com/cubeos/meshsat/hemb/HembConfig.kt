package com.cubeos.meshsat.hemb

/**
 * Bond group configuration — pushed from Hub or configured locally.
 */
data class HembConfig(
    val id: String,
    val label: String,
    val members: List<String>,  // interface IDs
    val costBudget: Double = 0.0,
)
