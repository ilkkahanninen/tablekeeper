package tablekeeper.print

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** HTML -> PDF. */
fun interface HtmlToPdf {
    fun pdf(html: String): ByteArray
}

/**
 * HTML -> PDF in headless Chromium (Playwright), so text is shaped like in a browser: the fonts' kerning and ligatures
 * reach the page, and CSS is a browser's (flexbox, real SVG). The page size is the template's `@page`.
 *
 * Playwright objects belong to the thread that made them, so one thread owns the browser and renders one document at a
 * time; the browser starts on the first print and again if it dies. A page sees only its own HTML and the app's fonts
 * (classpath `fonts/`, at `/fonts/…` as on the app's own pages): scripts are off and every other request is refused.
 */
class Chromium : HtmlToPdf, AutoCloseable {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "chromium").apply { isDaemon = true } }
    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private val fonts = ConcurrentHashMap<String, ByteArray>()

    override fun pdf(html: String): ByteArray =
        try { thread.submit<ByteArray> { render(html) }.get() } catch (e: ExecutionException) { throw e.cause ?: e }

    private fun render(html: String): ByteArray {
        val b = browser?.takeIf { it.isConnected } ?: launch()
        return b.newContext(Browser.NewContextOptions().setJavaScriptEnabled(false)).use { context ->
            context.route("**/*") { route -> serve(route, html) }
            val page = context.newPage()
            page.navigate(DOCUMENT)
            page.pdf(Page.PdfOptions().setPreferCSSPageSize(true).setPrintBackground(true))
        }
    }

    private fun serve(route: Route, html: String) {
        val url = route.request().url()
        val font = url.removePrefix(ORIGIN).takeIf { url.startsWith("${ORIGIN}fonts/") && it.endsWith(".ttf") }
            ?.let { path -> fonts.getOrPut(path) { javaClass.getResourceAsStream("/$path")?.readBytes() ?: return@let null } }
        when {
            url == DOCUMENT -> route.fulfill(Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html))
            font != null -> route.fulfill(Route.FulfillOptions().setContentType("font/ttf").setBodyBytes(font))
            else -> route.abort()
        }
    }

    private fun launch(): Browser {
        close(browser)
        // Just Chromium, installed ahead (`./gradlew installChromium`, the Dockerfile): Playwright would fetch every browser
        val p = playwright ?: Playwright.create(Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1")))
            .also { playwright = it }
        return p.chromium().launch().also { browser = it }
    }

    private fun close(b: Browser?) = runCatching { b?.close() }

    override fun close() {
        thread.submit { close(browser); playwright?.close() }.get()
        thread.shutdown()
    }

    private companion object {
        /** Where a page thinks it is: a name that can't be on any network, so nothing reaches out by accident. */
        const val ORIGIN = "http://print.invalid/"
        const val DOCUMENT = "${ORIGIN}document"
    }
}
