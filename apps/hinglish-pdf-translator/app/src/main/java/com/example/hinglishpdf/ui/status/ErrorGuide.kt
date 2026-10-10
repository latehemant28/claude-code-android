package com.example.hinglishpdf.ui.status

/** A next step offered with an error, always as a button. */
enum class GuideAction(val label: String) {
    RESUME("Try again"),
    FIX_KEY("Check the API key"),
    CHANGE_PROVIDER("Use another AI"),
    ADD_CREDIT("Open the provider's site"),
    SET_MODEL("Type a model name"),
    ACCEPT_TERMS("Read the Terms"),
    CHOOSE_OTHER_FILE("Choose another file"),
    SAVE_AGAIN("Save again"),
}

/**
 * An error, told as guidance: what happened, what the user can do, and the
 * buttons to do it. [keepsProgress] is true when nothing translated so far is
 * lost (almost always). [detail] is the original message, for "Details".
 */
data class Guidance(
    val title: String,
    val explanation: String,
    val primary: GuideAction,
    val secondary: GuideAction? = null,
    val keepsProgress: Boolean = true,
    val detail: String = "",
)

/**
 * Turns the pipeline's error messages into guidance (Norman: no blaming the
 * user, a clear way forward, never a forced restart). Matching is on the
 * messages the app itself produces; anything unknown still gets a way on.
 */
object ErrorGuide {

    fun of(message: String?): Guidance {
        val m = message.orEmpty()
        fun has(vararg words: String) = words.any { m.contains(it, ignoreCase = true) }
        val g = when {
            has("No API key") -> Guidance(
                "No AI key yet",
                "Translation needs a key from an AI provider. Add one, then continue: nothing is lost.",
                GuideAction.FIX_KEY,
            )
            has("rejected the API key", "API key not valid", "Check the key you saved", "invalid api key") -> Guidance(
                "The AI didn't accept your key",
                "The key may be incomplete, deleted on the provider's site, or meant for another provider. " +
                    "Paste it again or use another AI; your progress is saved.",
                GuideAction.FIX_KEY, GuideAction.CHANGE_PROVIDER,
            )
            has("no credit", "billing credit", "needs credit", "insufficient balance") -> Guidance(
                "Your AI account is out of credit",
                "Add credit on the provider's site, or switch to a provider with a free tier. Then continue from the same page.",
                GuideAction.ADD_CREDIT, GuideAction.CHANGE_PROVIDER,
            )
            has("daily", "per day", "quota of this API key is used up") -> Guidance(
                "Today's free limit is used up",
                "The provider allows only so much a day. Continue tomorrow from the same page, or switch to another AI now.",
                GuideAction.CHANGE_PROVIDER, GuideAction.RESUME,
            )
            has("None of these", "type a current model", "Model field") -> Guidance(
                "This AI's models have changed",
                "The provider retired the models the app knows. Type a current model name (from the provider's site), or use another AI.",
                GuideAction.SET_MODEL, GuideAction.CHANGE_PROVIDER,
            )
            has("Custom AI address") -> Guidance(
                "The Custom AI address isn't usable",
                "Enter the service's address, for example https://example.com/v1, in the AI provider card. Your progress is saved.",
                GuideAction.FIX_KEY, GuideAction.CHANGE_PROVIDER,
            )
            has("country or region", "region") -> Guidance(
                "This AI isn't available where you are",
                "The provider blocks requests from your region. Choose another AI; your progress is saved.",
                GuideAction.CHANGE_PROVIDER,
            )
            has("Terms of Use") -> Guidance(
                "Please read the Terms first",
                "Translation starts once you have accepted the Terms of Use.",
                GuideAction.ACCEPT_TERMS,
            )
            has("scanned", "No text found") -> Guidance(
                "This PDF is a scan",
                "Its pages are pictures, with no text to translate. Text recognition (OCR) is coming in a later update; " +
                    "for now, run the PDF through an OCR app and choose the new file.",
                GuideAction.CHOOSE_OTHER_FILE, keepsProgress = false,
            )
            has("password") -> Guidance(
                "This PDF is locked with a password",
                "Open it in a PDF app with its password and save an unlocked copy (Print → Save as PDF), then choose that copy.",
                GuideAction.CHOOSE_OTHER_FILE, keepsProgress = false,
            )
            has("Only PDF and EPUB", "Not an EPUB", "Could not open the selected file") -> Guidance(
                "This file can't be read as a book",
                "The app reads PDF and EPUB books. Choose the book file itself (not a link or a shortcut to it).",
                GuideAction.CHOOSE_OTHER_FILE, keepsProgress = false,
            )
            has("Downloads") -> Guidance(
                "The file couldn't be saved",
                "Check that the phone has free space, then save again. The translation itself is kept.",
                GuideAction.SAVE_AGAIN,
            )
            has("refused the request", "declined") -> Guidance(
                "The AI refused this request",
                "This can happen with some keys or regions. Check the key, or use another AI; your progress is saved.",
                GuideAction.CHANGE_PROVIDER, GuideAction.FIX_KEY,
            )
            has("internet", "took too long", "Gave up after", "is busy", "connection") -> Guidance(
                "Couldn't reach the AI",
                "Check the internet connection, then continue. Nothing is lost: it picks up at the same page.",
                GuideAction.RESUME,
            )
            else -> Guidance(
                "Translation stopped",
                "Something unexpected happened. Your progress is saved; try again, or use another AI if it keeps happening.",
                GuideAction.RESUME, GuideAction.CHANGE_PROVIDER,
            )
        }
        return g.copy(detail = m)
    }
}
