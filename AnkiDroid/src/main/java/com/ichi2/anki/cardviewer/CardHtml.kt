/*
 *  Copyright (c) 2021 David Allison <davidallisongithub@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.cardviewer

import com.ichi2.anki.R
import com.ichi2.libanki.*
import com.ichi2.libanki.template.MathJax
import com.ichi2.themes.HtmlColors
import com.ichi2.utils.JSONObject
import net.ankiweb.rsdroid.RustCleanup
import timber.log.Timber
import java.util.regex.Pattern

@RustCleanup("transition to an instance of TemplateRenderOutput")
class CardHtml(
    @RustCleanup("legacy")
    private val beforeSoundTemplateExpansion: String,
    private val ord: Int,
    private val nightModeInversion: Boolean,
    private val context: HtmlGenerator,
    /** The side that [beforeSoundTemplateExpansion] was generated from */
    private val side: Side,
    @RustCleanup("Slow function, only used with legacy code")
    private val getAnswerContentWithoutFrontSide_slow: (() -> String),
    private var questionAv: List<AvTag>? = null,
    private var answerAv: List<AvTag>? = null,
    private val usingBackend: Boolean = answerAv != null
) {
    fun getSoundTags(sideFor: Side): List<SoundOrVideoTag> {
        if (sideFor == this.side) {
            return getSoundsForCurrentSide()
        }

        if (sideFor == Side.BACK && side == Side.FRONT) {
            if (answerAv == null) {
                answerAv = Sound.extractTagsFromLegacyContent(getAnswerContentWithoutFrontSide_slow())
            }
            return answerAv!!.filterIsInstance(SoundOrVideoTag::class.java)
        }

        // Back wanting front, only possible if questionAv != null
        if (questionAv != null) {
            return questionAv!!.filterIsInstance(SoundOrVideoTag::class.java)
        }

        throw IllegalStateException("Attempted to get the front of the card when viewing back using legacy system")
    }

    @RustCleanup("unnecessarily complex")
    private fun getSoundsForCurrentSide(): List<SoundOrVideoTag> {
        // beforeSoundTemplateExpansion refers to the current side
        return if (this.side == Side.FRONT) {
            if (questionAv == null) {
                questionAv = Sound.extractTagsFromLegacyContent(beforeSoundTemplateExpansion)
            }
            questionAv!!.filterIsInstance(SoundOrVideoTag::class.java)
        } else {
            if (answerAv == null) {
                answerAv = Sound.extractTagsFromLegacyContent(getAnswerContentWithoutFrontSide_slow())
            }
            return answerAv!!.filterIsInstance(SoundOrVideoTag::class.java)
        }
    }

    fun getTemplateHtml(): String {
        val content = getContent()

        val requiresMathjax = MathJax.textContainsMathjax(content)

        val style = getStyle()
        val script = getScripts(requiresMathjax)
        val cardClass = getCardClass(requiresMathjax)

        Timber.v("content card = \n %s", content)
        Timber.v("::style:: / %s", style)

        return context.cardTemplate.render(content, style, script, cardClass)
    }

    private fun getContent(): String {
        var content = context.expandSounds(beforeSoundTemplateExpansion)
        content = CardAppearance.fixBoldStyle(content)
        if (nightModeInversion) {
            return HtmlColors.invertColors(content)
        }
        return content
    }

    private fun getStyle(): String {
        return context.cardAppearance.style
    }

    private fun getCardClass(requiresMathjax: Boolean): String {
        // CSS class for card-specific styling
        var cardClass: String = context.cardAppearance.getCardClass(ord + 1, context.currentTheme)
        if (requiresMathjax) {
            cardClass += " mathjax-needs-to-render"
        }
        return cardClass
    }

    private fun getScripts(requiresMathjax: Boolean): String {
        return when (requiresMathjax) {
            false -> ""
            true ->
                """        <script src="file:///android_asset/mathjax/conf.js"> </script>
        <script src="file:///android_asset/mathjax/tex-chtml.js"> </script>"""
        }
    }

    companion object {
        fun createInstance(card: Card, reload: Boolean, side: Side, context: HtmlGenerator): CardHtml {
            val content = displayString(card, reload, side, context)

            val nightModeInversion = context.cardAppearance.isNightMode && !context.cardAppearance.hasUserDefinedNightMode(card)

            val questionAv = card.render_output().question_av_tags
            val answerAv = card.render_output().answer_av_tags

            // legacy (slow) function to return the answer without the front side
            fun getAnswerWithoutFrontSideLegacy(): String = removeFrontSideAudio(card, card.a())

            return CardHtml(content, card.ord, nightModeInversion, context, side, ::getAnswerWithoutFrontSideLegacy, questionAv, answerAv)
        }

        /**
         * String, as it will be displayed in the web viewer.
         * Sound/video removed, image escaped...
         * Or warning if required
         * TODO: This is no longer entirely true as more post-processing occurs
         */
        private fun displayString(card: Card, reload: Boolean, side: Side, context: HtmlGenerator): String {
            if (side == Side.FRONT && card.isEmpty) {
                return context.resources.getString(R.string.empty_card_warning)
            }

            var content: String = if (side == Side.FRONT) card.q(reload) else card.a()
            content = Media.escapeImages(content)
            content = context.filterTypeAnswer(content, side)
            Timber.v("question: '%s'", content)
            return enrichWithQADiv(content, side)
        }

        /**
         * Adds a div html tag around the contents to have an indication, where answer/question is displayed
         *
         * @param content The content to surround with tags.
         * @param side whether the class attribute is set to "answer" or "question".
         * @return The enriched content
         */
        fun enrichWithQADiv(content: String?, side: Side): String {
            val sb = StringBuilder()
            sb.append("<div class=")
            if (side == Side.BACK) {
                sb.append(CardAppearance.ANSWER_CLASS)
            } else {
                sb.append(CardAppearance.QUESTION_CLASS)
            }
            sb.append(" id=\"qa\">")
            sb.append(content)
            sb.append("</div>")
            return sb.toString()
        }

        /**
         * @return the answer part of this card's template as entered by user, without any parsing
         */
        private fun getAnswerFormat(card: Card): String {
            val model = card.model()
            val template: JSONObject = if (model.isStd) {
                model.getJSONArray("tmpls").getJSONObject(card.ord)
            } else {
                model.getJSONArray("tmpls").getJSONObject(0)
            }
            return template.getString("afmt")
        }

        /**
         * Removes first occurrence in answerContent of any audio that is present due to use of
         * {{FrontSide}} on the answer.
         * @param card              The card to strip content from
         * @param answerContent     The content from which to remove front side audio.
         * @return The content stripped of audio due to {{FrontSide}} inclusion.
         */
        @JvmStatic
        fun removeFrontSideAudio(card: Card, answerContent: String): String {
            val answerFormat = getAnswerFormat(card)
            var newAnswerContent = answerContent
            if (answerFormat.contains("{{FrontSide}}")) { // possible audio removal necessary
                val frontSideFormat = card.render_output(false).question_text
                val audioReferences = Sound.SOUND_PATTERN.matcher(frontSideFormat)
                // remove the first instance of audio contained in "{{FrontSide}}"
                while (audioReferences.find()) {
                    newAnswerContent = newAnswerContent.replaceFirst(Pattern.quote(audioReferences.group()).toRegex(), "")
                }
            }
            return newAnswerContent
        }
    }
}
