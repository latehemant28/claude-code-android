package com.example.hinglishpdf.data.translate

/** The two output styles: Hindi or Marathi, written in Latin letters. */
enum class TargetLanguage(
    val label: String,
    /** The language the everyday words are translated into. */
    val baseLanguage: String,
    val vocabularyExamples: String,
    val flowExample: String,
    /** Example answer for the prompt's worked example (IDs 2 and 3). */
    val exampleBullet: String,
    val exampleParagraph: String,
) {
    HINGLISH(
        label = "Hinglish",
        baseLanguage = "Hindi",
        vocabularyExamples = "kaam, lekin, zaroori, samajh",
        flowExample = "Yeh process start karne ke liye, aapko next button par click karna hoga",
        exampleBullet = "Setup continue karne ke liye Next button par click karein.",
        exampleParagraph = "Yeh step zaroori hai kyunki isse aapki settings save hoti hain.",
    ),
    MINGLISH(
        label = "Minglish",
        baseLanguage = "Marathi",
        vocabularyExamples = "kaam, pan, mahatvacha, samajla",
        flowExample = "Hi process start karnyasathi, tumhala next button var click karava lagel",
        exampleBullet = "Setup continue karnyasathi Next button var click kara.",
        exampleParagraph = "Ha step mahatvacha aahe karan tyamule tumchi settings save hotat.",
    ),
}
