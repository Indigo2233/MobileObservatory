package com.indigo.mobileobservatory.stacking

import java.io.Closeable
import java.io.File
import java.util.UUID

/** Every public file is fully written before publication; failures remove this export's files. */
class StackExportTransaction(private val destinations: List<File>) : Closeable {
    private val temporary = destinations.associateWith { File(it.parentFile, ".${it.name}.${UUID.randomUUID()}.partial") }
    private val published = mutableListOf<File>()
    private var committed = false
    init { require(destinations.isNotEmpty() && destinations.map { it.canonicalPath }.distinct().size == destinations.size) }
    fun temporary(destination: File): File = temporary.getValue(destination)
    fun commit(check: () -> Unit = {}) {
        require(!committed)
        check()
        require(destinations.none { it.exists() }) { "export_collision" }
        require(temporary.values.all { it.isFile && it.length() > 0 }) { "incomplete_export" }
        for (destination in destinations) {
            check()
            check(!destination.exists() && temporary.getValue(destination).renameTo(destination)) { "storage_unavailable" }
            published += destination
        }
        check()
        committed = true
    }
    override fun close() {
        temporary.values.forEach { it.delete() }
        if (!committed) published.forEach { it.delete() }
    }
}
