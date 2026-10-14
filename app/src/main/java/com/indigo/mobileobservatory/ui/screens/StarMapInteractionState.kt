package com.indigo.mobileobservatory.ui.screens

/** Selection is an observing context. Dismissing its card must not discard it. */
internal data class StarMapInteractionState(
    val targetCardVisible: Boolean = false,
    val targetExpanded: Boolean = false,
    val panel: StarMapCornerPanel = StarMapCornerPanel.NONE
) {
    fun selectTarget() = copy(targetCardVisible = true, targetExpanded = false, panel = StarMapCornerPanel.NONE)

    fun openPanel(next: StarMapCornerPanel) = copy(
        panel = next,
        targetCardVisible = if (next == StarMapCornerPanel.NONE) targetCardVisible else false,
        targetExpanded = false
    )

    /** Closing a panel does not reveal a previously dismissed target card. */
    fun dismissTopLayer(): StarMapInteractionState = when {
        panel != StarMapCornerPanel.NONE -> copy(panel = StarMapCornerPanel.NONE)
        targetCardVisible -> copy(targetCardVisible = false, targetExpanded = false)
        else -> this
    }

    val hasInformationLayer: Boolean get() = panel != StarMapCornerPanel.NONE || targetCardVisible
}
