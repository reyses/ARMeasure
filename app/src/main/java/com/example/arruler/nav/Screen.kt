package com.example.arruler.nav

/** Top-level destinations; MainActivity holds the current one as plain state. */
sealed interface Screen {
    data object Measure : Screen
    data object Projects : Screen
    data object Settings : Screen
    data object Diagnostics : Screen

    /** Every saved object of every project, as a gallery. */
    data object Objects : Screen
    data class Plan(val projectId: String) : Screen
    data class Scan3D(val scanId: String) : Screen
    data class ObjectDetail(val scanId: String) : Screen
}
