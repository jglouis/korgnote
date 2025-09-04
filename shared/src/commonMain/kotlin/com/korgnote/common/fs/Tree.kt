package com.korgnote.common.fs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Node {
    val name: String
    // Full path from root (excluding root name if desired). Always uses '/' separators
    val path: String
}

@Serializable
@SerialName("folder")
data class Folder(
    override val name: String,
    override val path: String,
    val children: List<Node>
) : Node {
}

@Serializable
@SerialName("file")
data class File(
    override val name: String,
    override val path: String
) : Node

object TreeUtils {
    fun buildFromPaths(paths: List<String>, rootName: String = "Documents"): Folder {
        data class FolderBuilder(val name: String, val path: String) {
            val folders = mutableMapOf<String, FolderBuilder>()
            val files = mutableSetOf<String>()
        }
        val root = FolderBuilder(rootName, path = "")
        for (raw in paths) {
            val parts = raw.split('/').filter { it.isNotBlank() }
            if (parts.isEmpty()) continue
            var cursor = root
            var currentPath = ""
            for (i in parts.indices) {
                val segment = parts[i]
                if (i == parts.lastIndex) {
                    cursor.files.add(segment)
                } else {
                    val nextPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
                    currentPath = nextPath
                    cursor = cursor.folders.getOrPut(segment) {
                        FolderBuilder(segment, currentPath)
                    }
                }
            }
        }
        fun toNode(b: FolderBuilder, basePath: String): Folder {
            val childFolders = b.folders.values.sortedBy { it.name }.map { toNode(it, pathJoin(basePath, it.name)) }
            val childFiles = b.files.sorted().map { name -> File(name, pathJoin(basePath, name)) }
            return Folder(b.name, basePath, childFolders + childFiles)
        }
        return toNode(root, basePath = "")
    }

    private fun pathJoin(base: String, name: String): String = when {
        base.isEmpty() -> name
        else -> "$base/$name"
    }
}
