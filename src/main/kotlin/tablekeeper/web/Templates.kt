package tablekeeper.web

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.Loader
import java.io.File
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringWriter

/**
 * One Pebble engine for screen and print.
 * Template names resolve first against the classpath (`src/main/resources/templates/…`),
 * then against the modules directory (`modules/heart/print/landmark.peb`), so game
 * modules ship their own templates without a rebuild.
 */
class Templates(modulesDir: String, cache: Boolean = true) {
    private val engine: PebbleEngine = PebbleEngine.Builder()
        .loader(AppLoader(File(modulesDir)))
        .autoEscaping(true)
        .strictVariables(false)
        .cacheActive(cache)
        .build()

    fun render(name: String, model: Map<String, Any?>): String {
        val out = StringWriter()
        @Suppress("UNCHECKED_CAST")
        engine.getTemplate(name).evaluate(out, model as Map<String, Any>)
        return out.toString()
    }
}

private class AppLoader(modulesDir: File) : Loader<String> {
    private val root = modulesDir.canonicalFile
    private var charset = Charsets.UTF_8

    private fun moduleFile(name: String): File? {
        val f = File(root, name).canonicalFile
        return f.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
    }

    private fun classpathName(name: String) = "templates/" + name.removePrefix("/")

    override fun getReader(cacheKey: String): Reader {
        javaClass.classLoader.getResourceAsStream(classpathName(cacheKey))?.let {
            return InputStreamReader(it, charset)
        }
        val f = moduleFile(cacheKey) ?: throw IllegalArgumentException("Template not found: $cacheKey")
        return f.reader(charset)
    }

    override fun resourceExists(templateName: String): Boolean =
        javaClass.classLoader.getResource(classpathName(templateName)) != null || moduleFile(templateName) != null

    override fun setCharset(charset: String) { this.charset = java.nio.charset.Charset.forName(charset) }
    override fun setPrefix(prefix: String) {}
    override fun setSuffix(suffix: String) {}
    // All template names in this app are absolute ("print/base.peb"), so no relative resolution.
    override fun resolveRelativePath(relativePath: String, anchorPath: String): String = relativePath
    override fun createCacheKey(templateName: String): String = templateName
}
