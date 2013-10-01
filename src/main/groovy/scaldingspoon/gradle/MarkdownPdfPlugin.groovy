package scaldingspoon.gradle

import groovy.transform.InheritConstructors
import groovy.xml.DOMBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.parboiled.Rule
import org.pegdown.DefaultVerbatimSerializer
import org.pegdown.Extensions
import org.pegdown.LinkRenderer
import org.pegdown.Parser
import org.pegdown.ParsingTimeoutException
import org.pegdown.PegDownProcessor
import org.pegdown.Printer
import org.pegdown.ToHtmlSerializer
import org.pegdown.VerbatimSerializer
import org.pegdown.ast.RootNode
import org.pegdown.ast.VerbatimNode
import org.pegdown.plugins.InlinePluginParser
import org.pegdown.plugins.ToHtmlSerializerPlugin
import org.python.util.PythonInterpreter
import org.xhtmlrenderer.pdf.ITextRenderer

import java.util.concurrent.locks.ReentrantLock

class MarkdownPdfPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.plugins.apply(BasePlugin)

        def pythonInterpreter = new PythonInterpreter().with {
            it.exec('''
from pygments.styles import get_all_styles
from pygments.formatters import HtmlFormatter
from pygments.styles import get_style_by_name
from pygments import highlight
''')
            return it
        }

        pythonInterpreter.metaClass.locker = new ReentrantLock()

        project.tasks.create(name: "markdownPdfCodeStyles",
                overwrite: true,
                description: "Print a list of available code blocks",
                type: MarkdownPdfCodeStylesTask) { it.pythonInterpreter = pythonInterpreter }
        project.tasks.create(name: "markdownPdf",
                overwrite: true, description:
                "Creates a PDF from markdown files",
                type: MarkdownPdfTask) { it.pythonInterpreter = pythonInterpreter }
    }
}

class MarkdownPdfCodeStylesTask extends DefaultTask {
    PythonInterpreter pythonInterpreter

    @TaskAction
    def doit() {
        def styles = pythonInterpreter.eval("list(get_all_styles())")
        println "Available styles: ${styles}"
    }
}

class MarkdownPdfTask extends DefaultTask {
    PythonInterpreter pythonInterpreter

    @Optional
    @OutputFile
    File outputFile = this.project.file("${this.project.buildDir}/documentation/${this.project.name}.pdf")

    @Optional
    @InputFiles
    FileCollection inputFiles = this.project.fileTree("") { include "**/*.md" }

    @Optional
    @OutputFile
    File htmlOutputFile

    @Optional
    @InputFile
    File styleFile

    @Optional
    boolean toc = false

    @Optional
    int pdfExtensions = Extensions.ALL

    @Optional
    String codeStyle = "colorful"

    @TaskAction
    def doit() {
        def codeStyleCss = {
            pythonInterpreter.locker.lock()
            try {
                pythonInterpreter.set("codeStyle", codeStyle)
                pythonInterpreter.eval("HtmlFormatter(style=codeStyle).get_style_defs('.highlight')")
            } finally {
                pythonInterpreter.locker.unlock()
            }
        }()

        def docString = new ExtendedPegDownProcessor(pdfExtensions).markdownToHtml(
                inputFiles.collect { it.text }.join("\n" * 2).chars,
                new LinkRenderer(),
                new PygmentsVerbatimSerializers(pythonInterpreter: pythonInterpreter)
        )
        def domString = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN"
        "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
    <head>
        <style type="text/css">
            ${codeStyleCss}
        </style>
        ${
            if (styleFile) {
                "<link rel='stylesheet' type='text/css' href='${styleFile}' />"
            }
        }
    </head>
    <body>
        ${docString}
    </body>
</html>
        """
        if (htmlOutputFile) {
            htmlOutputFile.withPrintWriter {
                it.print domString
            }
        }
        def doc = DOMBuilder.parse(new StringReader(domString))
        def renderer = new ITextRenderer()
        renderer.setDocument(doc, null)
        renderer.layout()
        renderer.createPDF(outputFile.newOutputStream())
    }
}

class PygmentsVerbatimSerializers {
    @Delegate
    Map<String, VerbatimSerializer> serializerMap = new HashMap<String, VerbatimSerializer>()

    PythonInterpreter pythonInterpreter

    def get(String key) {
        if (serializerMap.containsKey(key)) {
            return serializerMap.get(key)
        }
        new VerbatimSerializer() {
            @Override
            void serialize(VerbatimNode verbatimNode, Printer printer) {
                pythonInterpreter.locker.lock()
                try {
                    def lexer = "${key.capitalize()}Lexer"
                    pythonInterpreter.set("code", verbatimNode.text)
                    pythonInterpreter.exec("from pygments.lexers import ${lexer}")
                    pythonInterpreter.exec("result = highlight(code, ${lexer}(), HtmlFormatter())")
                    printer.print('\n')
                    printer.print(pythonInterpreter.get("result", String))
                } finally {
                    pythonInterpreter.locker.unlock()
                }
            }
        }
    }
}

@InheritConstructors
class ExtendedPegDownProcessor extends PegDownProcessor {
    @Override
    String markdownToHtml(char[] markdownSource, LinkRenderer linkRenderer, Map<String, VerbatimSerializer> verbatimSerializerMap) {
        try {
            def astRoot = parseMarkdown(markdownSource)
            if (!verbatimSerializerMap.containsKey(VerbatimSerializer.DEFAULT)) {
                verbatimSerializerMap.put(VerbatimSerializer.DEFAULT, DefaultVerbatimSerializer.INSTANCE)
            }
            return new ExtendedToHtmlSerializer(linkRenderer, verbatimSerializerMap, []).toHtml(astRoot)
        } catch (ParsingTimeoutException e) {
            return null
        }
    }
}

@InheritConstructors
class ExtendedToHtmlSerializer extends ToHtmlSerializer {
    boolean generateChapters = false
    Map<String, VerbatimSerializer> verbatimSerializers

    ExtendedToHtmlSerializer(LinkRenderer linkRenderer, Map<String, VerbatimSerializer> verbatimSerializers, List<ToHtmlSerializerPlugin> plugins) {
        super(linkRenderer, verbatimSerializers, plugins)
        this.verbatimSerializers = verbatimSerializers
    }

    @Override
    void visit(VerbatimNode node) {
        def serializer = this.verbatimSerializers.get(node.type ?: VerbatimSerializer.DEFAULT)
        serializer.serialize(node, printer)
    }
}

class PluginParser extends Parser implements InlinePluginParser {
    public PluginParser() {
        super(Extensions.ALL, 2000L, DefaultParseRunnerProvider)
    }

    @Override
    Rule[] inlinePluginRules() {
        return [FencedCodeBlock()] as Rule[]
    }
}