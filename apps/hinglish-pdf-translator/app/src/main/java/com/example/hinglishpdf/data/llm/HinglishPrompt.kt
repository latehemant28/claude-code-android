package com.example.hinglishpdf.data.llm

/** The instruction prepended to every chunk sent to the model. */
object HinglishPrompt {

    const val INSTRUCTION =
        "Translate the following text into natural, conversational Hinglish using ONLY " +
            "the Latin/English alphabet. Keep technical terms in English. Do not output " +
            "Devanagari script. Output ONLY the translated text: "

    fun build(chunk: String): String = INSTRUCTION + chunk
}
