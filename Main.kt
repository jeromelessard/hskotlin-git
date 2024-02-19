package gitinternals

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.InflaterInputStream
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries

abstract class GitObject {
    abstract fun cat()
}

class GitFile(val name: String, val permission: String, val hash: String) {
    fun isDir()= permission == "40000"
}

class Blob : GitObject() {
    val lines = emptyList<String>().toMutableList()

    override fun cat() {
        println("*BLOB*")
        println(lines.joinToString("\n"))
    }
}

class Tree : GitObject() {
    val files = emptyList<GitFile>().toMutableList()

    override fun cat() {
        println("*TREE*")
        println(files.joinToString("\n") { "${it.permission} ${it.hash} ${it.name}" })
    }
}

class UserAction(private val name: String, private val email: String, private val timestamp: String) {

    fun author(): String {
        return "$name $email original timestamp: $timestamp"
    }

    fun committer(): String {
        return "$name $email commit timestamp: $timestamp"
    }

    fun log(): String {
        return "$name $email commit timestamp: $timestamp"
    }
}

class Commit(private val hash: String) : GitObject() {
    var tree: String = ""
    val parents = emptyList<String>().toMutableList()
    var author = UserAction("", "","")
    var committer= UserAction("", "","")
    val commitMessages = emptyList<String>().toMutableList()

    override fun cat() {
        println("*COMMIT*")
        println("tree: $tree")
        if (parents.isNotEmpty()) {
            println("parents: ${parents.joinToString(" | ")}")
        }
        println("author: ${author.author()}")
        println("committer: ${committer.committer()}")
        println("commit message:")
        println(commitMessages.joinToString("\n"))
    }

    fun log(merged: Boolean) {
        print("Commit: $hash")
        println(if (merged) " (merged)" else "")
        println( committer.log())
        println(commitMessages.joinToString("\n"))
    }

    fun parents(): List<Pair<String, Boolean>> {

        return when (parents.size) {
            1 -> listOf(Pair(parents.first(), false))
            2 -> listOf(Pair(parents.last(), true), Pair(parents.first(), false))
            else -> emptyList()
        }
    }
}

fun gitObjectToStream(location: File): InflaterInputStream {
    val contentAsZip = location.inputStream()
    return InflaterInputStream(contentAsZip)
}

fun nextLine(stream: InflaterInputStream): String {
    var line = ""
    while (stream.available() > 0) {
        val next = stream.read()
        if (next == 0 || next == '\n'.code) {
            break
        } else {
            line += Char(next)
        }
    }
    return line
}

@OptIn(ExperimentalUnsignedTypes::class)
fun ByteArray.toHexString() = asUByteArray().joinToString("") { it.toString(16).padStart(2, '0') }

fun nextTreeItem(stream: InflaterInputStream): String {
    var line = ""
    val sha = ByteArray(20)
    while (stream.available() > 0) {
        val next = stream.read()
        if (next == 0) {
            stream.read(sha)
            line += " "
            line += sha.toHexString()
            break
        } else {
            line += Char(next)
        }
    }
    return line
}

fun formattedTS(epoch: String, tz: String): String {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
        .format(Instant.ofEpochSecond(epoch.toLong()).atOffset(ZoneOffset.of(tz)))
}

fun formattedEmail(email: String) = email.removePrefix("<").removeSuffix(">")


fun readTree(tree: Tree, stream: InflaterInputStream) {
    while (stream.available() > 0) {
        val line = nextTreeItem(stream)
        val (perm, file, sha) = line.split(" ")
        tree.files.add(GitFile(permission =  perm, hash = sha, name = file))
    }
}

fun readBlob(blob: Blob, stream: InflaterInputStream) {
    while (stream.available() > 0) {
        blob.lines.add(nextLine(stream))
    }
}

fun readCommit(commit: Commit, stream: InflaterInputStream) {
    while (stream.available() > 0) {
        val l = nextLine(stream)
        if (l.isNotBlank()) {
            val line = l.split(" ")
            when (line[0]) {
                "tree" -> commit.tree = line[1]
                "parent" -> commit.parents.add(line[1])
                "author" -> commit.author = UserAction(line[1], formattedEmail(line[2]), formattedTS(line[3], line[4]))
                "committer" -> commit.committer =
                    UserAction(line[1], formattedEmail(line[2]), formattedTS(line[3], line[4]))
                else -> commit.commitMessages.add(line.joinToString(" "))
            }
        }
    }
}

fun target(vararg elements: String): File {
    return Path(elements.joinToString(File.separator)).toFile()
}

fun readObject(gitRepoLocation: String, objectHash: String): GitObject {
    val target = target(
        gitRepoLocation,
        "objects",
        objectHash.substring(0, 2),
        objectHash.substring(2)
    )


    val stream = gitObjectToStream(target)

    val (type, _) = nextLine(stream).split(" ")

    val gitObject: GitObject

    when (type) {
        "commit" -> {
            gitObject = Commit(objectHash)
            readCommit(gitObject, stream)
        }

        "blob" -> {
            gitObject = Blob()
            readBlob(gitObject, stream)
        }

        "tree" -> {
            gitObject = Tree()
            readTree(gitObject, stream)
        }

        else -> throw Exception("unknown")
    }
    return gitObject
}

fun catFile(gitRepoLocation: String) {
    println("Enter git object hash:")
    val objectHash = readln()

    readObject(gitRepoLocation, objectHash).cat()
}

fun recursiveTree(gitRepoLocation: String, tree: String, prefix: String ) {

    when (val tree = readObject(gitRepoLocation, tree)) {
        is Tree -> {
            for (file in tree.files) {
                if ( ! file.isDir()) {
                    println("$prefix${file.name}")
                } else {
                    val p = if (prefix.isBlank())   "${file.name}/" else "$prefix/${file.name}/"
                    recursiveTree(gitRepoLocation, file.hash, p)
                }
            }
        }
        else -> tree.cat()
    }
}

fun commitTree(gitRepoLocation: String) {
    println("Enter commit-hash:")
    val commitHash = readln()

    when (val commit = readObject(gitRepoLocation, commitHash)) {
        is Commit -> recursiveTree(gitRepoLocation, commit.tree, "")
        else -> throw Exception("wrong type")
    }
}

fun log(gitRepoLocation: String) {
    println("Enter branch name:")
    val branchName = readln()

    val target = target(
        gitRepoLocation,
        "refs", "heads", branchName
    )

    val inputHash = target.readLines()[0]

    val parents = listOf(Pair(inputHash, false)).toMutableList()

    while (parents.isNotEmpty()) {
        val (hash, merged) = parents.removeFirst()
        when (val o = readObject(gitRepoLocation, hash)) {
            is Commit -> {
                o.log(merged)
                println()
               if (! merged) {
                    parents.addAll(o.parents())
               }
            }
        }
    }

}

fun listBranches(gitRepoLocation: String) {
    val headFile = target(gitRepoLocation, "HEAD").readLines()[0]
    val (head) = "ref: refs/heads/(.*)".toRegex().find(headFile)!!.destructured

    val target = target(
        gitRepoLocation,
        "refs", "heads"
    )

    if (target.isDirectory) {
        for (branch in target.toPath().listDirectoryEntries("*").map {it.fileName.toString() }.sorted()) {
            val line = if (branch == head) "* $branch" else "  $branch"
            println(line)
        }
    }
}

fun main() {
    println("Enter .git directory location:")
    val gitRepoLocation = readln()

    println("Enter command:")

    when (val command = readln()) {
        "cat-file" -> catFile(gitRepoLocation)
        "list-branches" -> listBranches(gitRepoLocation)
        "log" -> log(gitRepoLocation)
        "commit-tree" -> commitTree(gitRepoLocation)
        else -> throw Exception("unknown command $command")
    }

}
