// Read a change with detail (GET /changes/{id} -> ChangeInfo, anonymous) and print a
// colored, Web-UI-style summary using Gerrit's own palette -- the Kotlin twin of the
// Rust/Go/Python/TypeScript/Java examples. The SDK is fetched from JitPack via Bazel
// (rules_jvm_external); every value comes from the generated gerrit-sdk-kotlin models.
//
// Gerrit's )]}' XSSI guard is stripped by GerritXssiInterceptor (an OkHttp Interceptor).
import com.google.gerrit.client.GerritXssiInterceptor
import com.google.gerrit.client.api.ChangesApi
import com.google.gerrit.client.model.AccountInfo
import com.google.gerrit.client.model.ChangeInfo
import com.google.gerrit.client.model.CommitInfo
import com.google.gerrit.client.model.CommonFileInfo
import com.google.gerrit.client.model.GitPerson

private val OPTIONS =
  listOf(
    "LABELS",
    "DETAILED_ACCOUNTS",
    "DETAILED_LABELS",
    "CURRENT_REVISION",
    "CURRENT_COMMIT",
    "CURRENT_FILES",
    "SUBMIT_REQUIREMENTS",
  )

private var useColor = false

fun main(args: Array<String>) {
  var url = "https://gerrit-review.googlesource.com"
  var change = "621763"
  var noColor = false
  var i = 0
  while (i < args.size) {
    when (args[i]) {
      "--url" -> url = args[++i]
      "--change" -> change = args[++i]
      "--no-color" -> noColor = true
    }
    i++
  }
  useColor =
    !noColor &&
      System.getenv("NO_COLOR") == null &&
      (System.getenv("CLICOLOR_FORCE") != null || System.console() != null)

  val base = url.trimEnd('/')
  val api = ChangesApi(base, GerritXssiInterceptor.client())
  try {
    printChange(base, api.getChangesChangeId(change, o = OPTIONS))
  } catch (e: Exception) {
    System.err.println("error: ${e.message}")
    kotlin.system.exitProcess(1)
  }
}

private fun printChange(base: String, ci: ChangeInfo) {
  println(rule())
  println("  ${statusBadge(ci)}  ${sgr("#${ci.number ?: 0}", BOLD)}")
  println("  ${sgr(ci.subject ?: "", BOLD)}")
  println(rule())
  println("  ${fg("$base/c/${ci.project ?: ""}/+/${ci.number ?: 0}", BLUE_700)}")

  section("Change Info")
  row("Owner", account(ci.owner))
  currentCommit(ci)?.let {
    row("Author", person(it.author))
    row("Committer", person(it.committer))
  }
  row("Repo | Branch", "${link(ci.project ?: "")} | ${link(ci.branch ?: "")}")
  row("Change-Id", link(ci.changeId ?: ""))
  ci.topic?.takeIf { it.isNotEmpty() }?.let { row("Topic", link(it)) }
  ci.hashtags?.takeIf { it.isNotEmpty() }?.let { row("Hashtags", link(it.joinToString(", "))) }
  flagChips(ci).takeIf { it.isNotEmpty() }?.let { row("Flags", it.joinToString("  ")) }
  row("Strategy", ci.submitType?.value?.let { pascal(it) } ?: "")
  parentCommit(ci).takeIf { it.isNotEmpty() }?.let { row("Parent", link(it.take(12))) }
  row("Patch set", ci.currentRevisionNumber?.toString() ?: "?")
  row("Updated", ci.updated ?: "")
  row("Size", plusminus(ci.insertions ?: 0, ci.deletions ?: 0))
  row("Comments", commentsSummary(ci))

  val reviewers = ci.reviewers.orEmpty()
  for ((key, title) in listOf("REVIEWER" to "Reviewers", "CC" to "CC")) {
    val people = reviewers[key].orEmpty()
    if (people.isEmpty()) continue
    section(title)
    people.forEach { println("    ${account(it)}") }
  }

  ci.submitRequirements?.takeIf { it.isNotEmpty() }?.let { reqs ->
    section("Submit Requirements")
    for (r in reqs) {
      val (icon, text) = reqParts(r.status?.value ?: "")
      println("    $icon ${(r.name ?: "").padEnd(26)} $text")
    }
  }

  ci.labels?.takeIf { it.isNotEmpty() }?.let { labels ->
    section("Votes")
    for (name in labels.keys.sorted()) {
      val chips =
        labels[name]?.all.orEmpty().filter { (it.value ?: 0) != 0 }.map {
          voteChip(it.value ?: 0, it.name ?: "")
        }
      println("    ${name.padEnd(22)} ${if (chips.isEmpty()) sgr("—", DIM) else chips.joinToString("  ")}")
    }
  }

  currentFiles(ci)?.takeIf { it.isNotEmpty() }?.let { files ->
    section("Files (patch set ${ci.currentRevisionNumber ?: "?"})")
    val paths =
      files.keys.sortedWith(compareBy({ it != "/COMMIT_MSG" }, { it.lowercase() }))
    for (p in paths) {
      val f = files.getValue(p)
      val (letter, color) = fileStatus(f.status)
      val name =
        when {
          p == "/COMMIT_MSG" -> "Commit message"
          !f.oldPath.isNullOrEmpty() -> "${f.oldPath} → $p"
          else -> p
        }
      println("    ${fg(letter, color)} ${name.padEnd(52)} ${plusminus(f.linesInserted ?: 0, f.linesDeleted ?: 0)}")
    }
  }
  println(rule())
}

// ---- model accessors ----------------------------------------------------------

private fun currentCommit(ci: ChangeInfo): CommitInfo? =
  ci.currentRevision?.let { ci.revisions?.get(it)?.commit }

private fun currentFiles(ci: ChangeInfo): Map<String, CommonFileInfo>? =
  ci.currentRevision?.let { ci.revisions?.get(it)?.files }

private fun parentCommit(ci: ChangeInfo): String =
  currentCommit(ci)?.parents?.firstOrNull()?.commit ?: ""

private fun account(a: AccountInfo?): String {
  if (a == null) return "—"
  return when {
    !a.name.isNullOrEmpty() && !a.email.isNullOrEmpty() -> named(a.name!!, a.email!!)
    !a.name.isNullOrEmpty() -> sgr(a.name!!, BOLD)
    a.accountId != null -> "account #${a.accountId}"
    else -> "—"
  }
}

private fun person(p: GitPerson?): String {
  if (p == null || (p.name.isNullOrEmpty() && p.email.isNullOrEmpty())) return "—"
  return named(p.name ?: "", p.email ?: "")
}

// Bold name, dim <email>. No blue -- reserve blue for links.
private fun named(name: String, email: String): String = "${sgr(name, BOLD)} ${sgr("<$email>", DIM)}"

private fun flagChips(ci: ChangeInfo): List<String> {
  val f = mutableListOf<String>()
  if (ci.workInProgress == true) f.add(chip(" WIP ", WHITE, WIP_BROWN))
  if (ci.isPrivate == true) f.add(chip(" Private ", WHITE, PURPLE_500))
  if (ci.mergeable == true) f.add(fg("mergeable", GREEN_700))
  if (ci.submittable == true) f.add(fg("submittable", GREEN_700))
  return f
}

private fun commentsSummary(ci: ChangeInfo): String {
  val total = ci.totalCommentCount ?: 0
  val unresolved = ci.unresolvedCommentCount ?: 0
  val resolved = maxOf(total - unresolved, 0)
  val openColor = if (unresolved > 0) RED_600 else GREEN_700
  return "$total total  (${fg("$resolved resolved", GREEN_700)}, ${fg("$unresolved unresolved", openColor)})"
}

private fun pascal(s: String): String =
  s.split("_").joinToString("") { it.lowercase().replaceFirstChar(Char::uppercase) }

// ---- styling ------------------------------------------------------------------

private const val ESC = ""
private const val BOLD = "1"
private const val DIM = "2"
private val WHITE = intArrayOf(255, 255, 255)
private val BLACK = intArrayOf(0, 0, 0)
private val GRAY_700 = intArrayOf(95, 99, 104)
private val YELLOW_700 = intArrayOf(242, 153, 0)
private val WIP_BROWN = intArrayOf(121, 85, 72)
private val PURPLE_500 = intArrayOf(161, 66, 244)
private val GREEN_700 = intArrayOf(24, 128, 56)
private val GREEN_300 = intArrayOf(129, 201, 149)
private val RED_300 = intArrayOf(242, 139, 130)
private val RED_600 = intArrayOf(217, 48, 37)
private val BLUE_700 = intArrayOf(25, 103, 210)
private val HEADER_INDIGO = intArrayOf(62, 78, 138)

private fun sgr(s: String, code: String) = if (useColor) "$ESC[${code}m$s$ESC[0m" else s

private fun fg(s: String, c: IntArray) =
  if (useColor) "$ESC[38;2;${c[0]};${c[1]};${c[2]}m$s$ESC[0m" else s

private fun chip(s: String, f: IntArray, b: IntArray) =
  if (useColor) "$ESC[38;2;${f[0]};${f[1]};${f[2]};48;2;${b[0]};${b[1]};${b[2]}m$s$ESC[0m" else s

private fun link(s: String) = fg(s, BLUE_700)

private fun rule() = fg("─".repeat(76), HEADER_INDIGO)

private fun section(title: String) {
  println()
  println("  ${sgr(title.uppercase(), BOLD)}")
}

private fun row(label: String, value: String) = println("    ${sgr(label.padEnd(14), DIM)}$value")

private fun statusBadge(ci: ChangeInfo): String {
  val raw = (ci.status?.value ?: "").uppercase()
  val (label, fgc, bg) =
    when {
      raw == "MERGED" -> Triple("Merged", WHITE, GRAY_700)
      raw == "ABANDONED" -> Triple("Abandoned", WHITE, GRAY_700)
      ci.workInProgress == true -> Triple("WIP", WHITE, WIP_BROWN)
      ci.isPrivate == true -> Triple("Private", WHITE, PURPLE_500)
      else -> Triple("Active", BLACK, YELLOW_700)
    }
  return chip(" $label ", fgc, bg)
}

private fun voteChip(v: Int, who: String): String {
  val bg = if (v > 0) GREEN_300 else RED_300
  val sign = if (v > 0) "+$v" else "$v"
  return "${chip(" $sign ", BLACK, bg)} $who"
}

private fun plusminus(ins: Int, del: Int) = "${fg("+$ins", GREEN_700)} ${fg("-$del", RED_600)}"

private fun reqParts(status: String): Pair<String, String> {
  val display = if (status.isEmpty()) "" else pascal(status)
  return when (status) {
    "SATISFIED" -> fg("✓", GREEN_700) to fg(display, GREEN_700)
    "UNSATISFIED" -> fg("✗", RED_600) to fg(display, RED_600)
    else -> sgr("○", DIM) to sgr(display, DIM)
  }
}

private fun fileStatus(s: String?): Pair<String, IntArray> =
  when (s) {
    "A" -> "A" to GREEN_700
    "D" -> "D" to RED_600
    "R" -> "R" to BLUE_700
    "C" -> "C" to BLUE_700
    "W" -> "W" to PURPLE_500
    else -> "M" to GRAY_700
  }
