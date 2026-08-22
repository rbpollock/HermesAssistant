package com.example.hermesassistant.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Streaming-friendly markdown renderer for assistant messages — the
 * Compose port of the desktop's Streamdown-backed MarkdownText. Subset:
 * headings, paragraphs (soft line breaks preserved), bullet/numbered
 * lists, blockquotes, fenced code blocks with a language label, inline
 * code, bold, italic, strikethrough, and clickable links.
 *
 * The text is re-parsed on every delta flush (like the desktop re-renders
 * the streamdown output); unclosed tokens degrade to literal text until
 * the next flush completes them.
 */

private const val URL_TAG = "URL"

// Compiled once; parseBlocks/buildInline run on every delta flush.
private val TOKEN_REGEX = Regex(
    """(`[^`\n]+`|\[[^\]\n]+]\([^)\n]+\)|\*\*[^*\s][^*\n]*\*\*|~~[^~\n]+~~|\*[^*\s][^*\n]*\*)"""
)
private val HEADING_REGEX = Regex("^#{1,6}\\s+.*")
private val BULLET_REGEX = Regex("^[-*+]\\s+.*")
private val NUMBERED_REGEX = Regex("^\\d+[.)]\\s+.*")
private val QUOTE_REGEX = Regex("^>\\s?.*")

private sealed class RenderBlock {
    data class Paragraph(val inline: AnnotatedString) : RenderBlock()
    data class Heading(val inline: AnnotatedString, val level: Int) : RenderBlock()
    data class CodeBlock(val code: String, val language: String) : RenderBlock()
    data class ListItem(val inline: AnnotatedString, val bullet: String) : RenderBlock()
    data class BlockQuote(val inline: AnnotatedString) : RenderBlock()
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is RenderBlock.Paragraph ->
                    InlineText(block.inline, fontSize)

                is RenderBlock.Heading ->
                    InlineText(block.inline, headingSize(block.level), bold = true)

                is RenderBlock.CodeBlock ->
                    CodeBlockView(block)

                is RenderBlock.ListItem ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = block.bullet,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = fontSize,
                            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                        )
                        InlineText(block.inline, fontSize, modifier = Modifier.weight(1f))
                    }

                is RenderBlock.BlockQuote ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(vertical = 2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        )
                        Spacer(Modifier.width(8.dp))
                        InlineText(
                            block.inline,
                            fontSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
            }
        }
    }
}

private fun headingSize(level: Int): TextUnit = when (level) {
    1 -> 20.sp
    2 -> 18.sp
    3 -> 16.sp
    else -> 14.sp
}

@Composable
private fun InlineText(
    annotated: AnnotatedString,
    fontSize: TextUnit,
    bold: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    ClickableText(
        text = annotated,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            lineHeight = if (fontSize == 13.sp) 19.sp else (fontSize.value + 6f).sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        ),
        modifier = modifier,
        onClick = { offset ->
            annotated.getStringAnnotations(URL_TAG, offset, offset).firstOrNull()?.let { ann ->
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ann.item)))
                } catch (e: Exception) {
                    // No handler for the scheme — ignore.
                }
            }
        },
    )
}

@Composable
private fun CodeBlockView(block: RenderBlock.CodeBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0B1220))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (block.language.isNotEmpty()) {
            Text(
                text = block.language,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(
            text = block.code.trimEnd('\n'),
            color = Color(0xFFD7DEE8),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

// ---------------------------------------------------------------------
// Parsers (pure; re-run per delta flush — chat-length text is cheap)
// ---------------------------------------------------------------------

private fun parseBlocks(text: String): List<RenderBlock> {
    val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    val blocks = mutableListOf<RenderBlock>()
    val para = mutableListOf<String>()

    fun flushPara() {
        if (para.isNotEmpty()) {
            blocks.add(RenderBlock.Paragraph(buildInline(para.joinToString("\n"))))
            para.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.isEmpty() -> flushPara()

            t.startsWith("```") -> {
                flushPara()
                val lang = t.removePrefix("```").trim()
                val code = mutableListOf<String>()
                i++
                var closed = false
                while (i < lines.size && !closed) {
                    if (lines[i].trim().startsWith("```")) closed = true
                    else code.add(lines[i])
                    i++
                }
                blocks.add(RenderBlock.CodeBlock(code.joinToString("\n"), lang))
                continue // i already advanced past the closing fence
            }

            t.matches(HEADING_REGEX) -> {
                flushPara()
                val level = t.takeWhile { it == '#' }.length
                blocks.add(RenderBlock.Heading(buildInline(t.substring(level).trim()), level))
            }

            t.matches(QUOTE_REGEX) -> {
                flushPara()
                blocks.add(RenderBlock.BlockQuote(buildInline(t.removePrefix(">").trim())))
            }

            t.matches(BULLET_REGEX) -> {
                flushPara()
                blocks.add(RenderBlock.ListItem(buildInline(t.substring(1).trim()), "•"))
            }

            t.matches(NUMBERED_REGEX) -> {
                flushPara()
                val n = t.takeWhile { it.isDigit() }
                blocks.add(RenderBlock.ListItem(buildInline(t.substring(n.length + 1).trim()), "$n."))
            }

            else -> para.add(line)
        }
        i++
    }
    flushPara()
    return blocks
}

/** Inline spans: inline code, links, bold, strikethrough, italic. */
private fun buildInline(raw: String): AnnotatedString {
    val out = AnnotatedString.Builder()
    var pos = 0
    TOKEN_REGEX.findAll(raw).forEach { m ->
        if (m.range.first > pos) out.append(raw.substring(pos, m.range.first))
        val tok = m.value
        when {
            tok.startsWith("`") -> out.withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    background = Color(0xFF1E293B),
                    color = Color(0xFFE2E8F0),
                )
            ) { out.append(tok.removeSurrounding("`")) }

            tok.startsWith("[") -> {
                val close = tok.indexOf("](")
                val linkText = tok.substring(1, close)
                val url = tok.substring(close + 2, tok.length - 1)
                out.pushStringAnnotation(URL_TAG, url)
                out.withStyle(
                    SpanStyle(
                        color = Color(0xFF7CB8FF),
                        textDecoration = TextDecoration.Underline,
                    )
                ) { out.append(linkText) }
                out.pop()
            }

            tok.startsWith("**") -> out.withStyle(
                SpanStyle(fontWeight = FontWeight.Bold)
            ) { out.append(tok.removeSurrounding("**")) }

            tok.startsWith("~~") -> out.withStyle(
                SpanStyle(textDecoration = TextDecoration.LineThrough)
            ) { out.append(tok.removeSurrounding("~~")) }

            tok.startsWith("*") -> out.withStyle(
                SpanStyle(fontStyle = FontStyle.Italic)
            ) { out.append(tok.removeSurrounding("*")) }
        }
        pos = m.range.last + 1
    }
    if (pos < raw.length) out.append(raw.substring(pos))
    return out.toAnnotatedString()
}
