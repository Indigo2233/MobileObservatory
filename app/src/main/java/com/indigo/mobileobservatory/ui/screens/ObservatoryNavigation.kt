package com.indigo.mobileobservatory.ui.screens

/** Only workspaces are roots. Switching them never builds a history of tabs. */
enum class ObservatoryWorkspace { STAR_MAP, CAPTURE, MEDIA, EQUIPMENT }

enum class ObservatoryDestination {
    SETTINGS, PREPARATION, POINTING_SETUP, AUXILIARY_CALIBRATION, TARGETS, PUSH_TO, PUSH_TO_AUXILIARY, PHONE_DEBUG,
    PLATE_SOLVE, POLAR_ALIGNMENT, GUIDE, STACK, PLAYER, SEQUENCE
}

data class ObservatoryPage(val id: Int, val destination: ObservatoryDestination,
                           val argument: String? = null, val category: String? = null)

/** Navigation has no device side effects. A Back gesture only commits when completed. */
data class ObservatoryNavigation(
    val workspace: ObservatoryWorkspace = ObservatoryWorkspace.CAPTURE,
    val pages: List<ObservatoryPage> = emptyList(),
    val nextId: Int = 1,
    val suspendedPages: Map<ObservatoryWorkspace, List<ObservatoryPage>> = emptyMap()
) {
    val current: ObservatoryDestination? get() = pages.lastOrNull()?.destination
    val canGoBack: Boolean get() = pages.isNotEmpty() || workspace != ObservatoryWorkspace.CAPTURE

    val workspaceStacks get() = suspendedPages + (workspace to pages)
    val allPages get() = workspaceStacks.values.flatten()

    fun select(workspace: ObservatoryWorkspace): ObservatoryNavigation = if (workspace == this.workspace) this else
        copy(workspace = workspace, pages = suspendedPages[workspace].orEmpty(),
            suspendedPages = (suspendedPages + (this.workspace to pages)) - workspace)
    fun open(destination: ObservatoryDestination, argument: String? = null, category: String? = null): ObservatoryNavigation {
        // Each stack mode has one coordinator/task. Reopening it must not create an
        // older-looking screen backed by a newer source. Global tools also reuse their page.
        if (destination in setOf(ObservatoryDestination.STACK, ObservatoryDestination.SETTINGS, ObservatoryDestination.GUIDE)) {
            workspaceStacks.forEach { (owner, stack) ->
                val existing = stack.indexOfLast { it.destination == destination && it.argument == argument }
                if (existing >= 0) return select(owner).copy(pages = stack.take(existing + 1))
            }
        }
        return if (current == destination && pages.lastOrNull()?.argument == argument && pages.lastOrNull()?.category == category) this
        else copy(pages = pages + ObservatoryPage(nextId, destination, argument, category), nextId = nextId + 1)
    }
    fun back(): ObservatoryNavigation = when {
        pages.isNotEmpty() -> copy(pages = pages.dropLast(1))
        workspace != ObservatoryWorkspace.CAPTURE -> select(ObservatoryWorkspace.CAPTURE)
        else -> this
    }

    fun save(): List<String> = listOf("workspace-stacks-v2", workspace.name, nextId.toString()) +
        ObservatoryWorkspace.entries.filter { it in workspaceStacks }.flatMap { owner ->
            val stack = workspaceStacks.getValue(owner)
            listOf(owner.name, stack.size.toString()) + stack.flatMap {
                listOf(it.id.toString(), it.destination.name, it.argument.orEmpty(), it.category.orEmpty())
            }
        }

    companion object {
        fun restore(values: List<String>): ObservatoryNavigation = runCatching {
            fun page(fields: List<String>) = ObservatoryPage(fields[0].toInt(), ObservatoryDestination.valueOf(fields[1]),
                fields[2].ifEmpty { null }, fields[3].ifEmpty { null })
            val restored = if (values.firstOrNull() == "workspace-stacks-v2") {
                val owner = ObservatoryWorkspace.valueOf(values[1]); val next = values[2].toInt()
                val stacks = linkedMapOf<ObservatoryWorkspace, List<ObservatoryPage>>()
                var index = 3
                while (index < values.size) {
                    val workspace = ObservatoryWorkspace.valueOf(values[index++]); require(workspace !in stacks)
                    val count = values[index++].toInt(); require(count in 0..64 && index + count * 4 <= values.size)
                    stacks[workspace] = values.subList(index, index + count * 4).chunked(4).map(::page)
                    index += count * 4
                }
                require(owner in stacks)
                ObservatoryNavigation(owner, stacks.getValue(owner), next, stacks - owner)
            } else {
                require(values.size >= 2 && (values.size - 2) % 4 == 0)
                ObservatoryNavigation(ObservatoryWorkspace.valueOf(values[0]), values.drop(2).chunked(4).map(::page), values[1].toInt())
            }
            require(restored.allPages.size <= 256 && restored.allPages.all { it.id > 0 } &&
                restored.allPages.map { it.id }.toSet().size == restored.allPages.size &&
                restored.nextId > (restored.allPages.maxOfOrNull { it.id } ?: 0))
            restored
        }.getOrDefault(ObservatoryNavigation())
    }
}
