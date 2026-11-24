package com.hencohen.grocery.source

import com.hencohen.grocery.model.Chain
import com.hencohen.grocery.model.PortalFile
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.parameters

/**
 * Cerberus "PublishedPrices" portal (url.publishedprices.co.il), shared by Rami Levy, Tiv Taam,
 * Keshet Teamim, Yohananof, Osher Ad and others. Each chain has a public username and an empty
 * password (a few have a published password).
 *
 * Flow: GET /login (csrftoken) → POST /login/user → GET /file (fresh csrftoken) →
 * POST /file/json/dir (JSON listing, `fname` per file) → GET /file/d/<fname> on the same session.
 * The session lives in the HttpClient's cookie storage, so list right before downloading and don't
 * interleave two Cerberus chains (sync runs chains one after another).
 *
 * UNVERIFIED against the live portal from here (it only answers Israeli IPs). The reference
 * scraper also supports the same accounts over FTP (url.retail.publishedprices.co.il) — a fallback
 * if the web flow changes.
 */
class CerberusSource(
    private val http: HttpClient,
    override val chain: Chain,
    private val username: String,
    private val password: String = "",
) : PriceSource {

    override suspend fun listFiles(kind: FileKind, storeId: String?): List<PortalFile> {
        val loginToken = csrfToken(http.get("$BASE/login").bodyAsText())
            ?: error("${chain.displayName}: no csrftoken on login page")

        val login = http.submitForm(
            url = "$BASE/login/user",
            formParameters = parameters {
                append("r", "")
                append("username", username)
                append("password", password)
                append("Submit", "Sign in")
                append("csrftoken", loginToken)
            },
        )
        check(login.status.value in 200..399) { "${chain.displayName}: login failed (HTTP ${login.status.value})" }

        // A rejected login bounces /file back to the login page (which also carries a token).
        val filesResponse = http.get("$BASE/file")
        check(!filesResponse.request.url.encodedPath.startsWith("/login")) {
            "${chain.displayName}: login rejected for user '$username'"
        }
        val token = csrfToken(filesResponse.bodyAsText())
            ?: error("${chain.displayName}: no csrftoken on the files page")

        val listing = http.submitForm(
            url = "$BASE/file/json/dir",
            formParameters = parameters {
                append("sEcho", "1")
                append("iColumns", "5")
                append("sColumns", ",,,,")
                append("iDisplayStart", "0")
                append("iDisplayLength", "100000")
                append("mDataProp_0", "fname")
                append("sSearch", "")
                append("bRegex", "false")
                append("cd", "/")
                append("csrftoken", token)
            },
        ).bodyAsText()

        return parseListing(listing, chain)
    }

    companion object {
        const val BASE = "https://url.publishedprices.co.il"

        private val csrfPatterns = listOf(
            Regex("""name=["']csrftoken["'][^>]*content=["']([^"']+)["']"""),
            Regex("""content=["']([^"']+)["'][^>]*name=["']csrftoken["']"""),
            Regex("""name=["']csrftoken["'][^>]*value=["']([^"']+)["']"""),
        )
        private val fnamePattern = Regex(""""fname"\s*:\s*"([^"]+)"""")

        fun csrfToken(html: String): String? =
            csrfPatterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }

        fun parseListing(json: String, chain: Chain): List<PortalFile> =
            fnamePattern.findAll(json)
                .map { it.groupValues[1].replace("\\/", "/") }
                .distinct()
                .mapNotNull { name -> portalFile(name, "$BASE/file/d/$name", chain) }
                .toList()
    }
}
