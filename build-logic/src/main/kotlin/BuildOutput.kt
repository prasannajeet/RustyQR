import org.gradle.api.file.FileTreeElement
import org.gradle.api.specs.Spec
import java.io.File

/**
 * Matches a file under the module's own `build/` directory (UniFFI bindings, generated sources, reports).
 *
 * Judged on the path relative to [moduleRoot], so a checkout that happens to live under some `.../build/...`
 * directory is still linted, and a source package that is itself named `build` is not mistaken for build output.
 * Built here rather than in the convention plugin script so the returned spec captures only [moduleRoot], not the
 * script object, which the configuration cache cannot serialize.
 */
internal fun buildOutputSpec(moduleRoot: File): Spec<FileTreeElement> =
    Spec { element ->
        val relativePath = element.file.relativeTo(moduleRoot).invariantSeparatorsPath
        relativePath.startsWith("build/")
    }
