package me.rerere.rikkahub.ui.pages.study

import me.rerere.rikkahub.data.model.StudySubject
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtractPlainTextTest {

    @Test
    fun `inline formula text extracted`() {
        assertEquals("解 log_2 x=3", extractPlainText("""解 ${'$'}\log_2 x=3${'$'}"""))
    }

    @Test
    fun `block formula dropped`() {
        assertEquals("求 x", extractPlainText("""求 ${'$'}${'$'}x=\frac{a}{b}${'$'}${'$'} x"""))
    }

    @Test
    fun `greek symbol mapped`() {
        assertEquals("角 α 的度数", extractPlainText("""角 ${'$'}\alpha${'$'} 的度数"""))
    }

    @Test
    fun `frac becomes slash`() {
        assertEquals("a/b", extractPlainText("""${'$'}\frac{a}{b}${'$'}"""))
    }

    @Test
    fun `plain text unchanged`() {
        assertEquals("牛顿第二定律", extractPlainText("牛顿第二定律"))
    }

    @Test
    fun `bare latex command simplified`() {
        assertEquals("log_2 8", extractPlainText("""\log_2 8"""))
    }
}

class BareLatexWrapTest {

    @Test
    fun `bare log wrapped`() {
        assertEquals("解：${'$'}\\log_2 x=3${'$'}", wrapBareLatex("""解：\log_2 x=3"""))
    }

    @Test
    fun `already dollar wrapped untouched`() {
        assertEquals("值 ${'$'}x^2${'$'} 不变", wrapBareLatex("值 ${'$'}x^2${'$'} 不变"))
    }

    @Test
    fun `inline code untouched`() {
        assertEquals("代码 `\\n` 保留", wrapBareLatex("代码 `\\n` 保留"))
    }

    @Test
    fun `plain backslash untouched`() {
        assertEquals("路径 C:\\dir\\file", wrapBareLatex("""路径 C:\dir\file"""))
    }

    @Test
    fun `block formula not double wrapped`() {
        assertEquals("${'$'}${'$'}\\frac{a}{b}${'$'}${'$'}", wrapBareLatex("""${'$'}${'$'}\frac{a}{b}${'$'}${'$'}"""))
    }

    @Test
    fun `block formula with command not double wrapped`() {
        assertEquals("${'$'}${'$'}x=\\log_2 8${'$'}${'$'}", wrapBareLatex("""${'$'}${'$'}x=\log_2 8${'$'}${'$'}"""))
    }

    @Test
    fun `inline formula not double wrapped`() {
        assertEquals("公式 ${'$'}\\times${'$'} 结果", wrapBareLatex("""公式 ${'$'}\times${'$'} 结果"""))
    }
}

class StudySubjectTest {

    @Test
    fun `normalize known codes`() {
        assertEquals("math", StudySubject.normalize("math"))
        assertEquals("math", StudySubject.normalize("MATH"))
        assertEquals("mechanics", StudySubject.normalize("mechanics"))
    }

    @Test
    fun `normalize chinese alias`() {
        assertEquals("mechanics", StudySubject.normalize("机原"))
        assertEquals("politics", StudySubject.normalize("政治"))
    }

    @Test
    fun `normalize unknown falls back to other`() {
        assertEquals("other", StudySubject.normalize("biology"))
        assertEquals("other", StudySubject.normalize(""))
        assertEquals("other", StudySubject.normalize(null))
    }

    @Test
    fun `name maps code to chinese`() {
        assertEquals("数学", StudySubject.name("math"))
        assertEquals("机械原理", StudySubject.name("mechanics"))
        assertEquals("其他", StudySubject.name("biology"))
    }
}
