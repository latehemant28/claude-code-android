package com.example.hinglishpdf.data.db

import androidx.room.TypeConverter
import com.example.hinglishpdf.data.document.DocBlock
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Stores block lists as JSON text columns. */
class Converters {
    private val gson = Gson()
    private val blocksType = object : TypeToken<List<DocBlock>>() {}.type
    private val stringsType = object : TypeToken<List<String?>>() {}.type

    @TypeConverter
    fun blocksToJson(blocks: List<DocBlock>?): String? = blocks?.let { gson.toJson(it, blocksType) }

    @TypeConverter
    fun jsonToBlocks(json: String?): List<DocBlock>? = json?.let { gson.fromJson(it, blocksType) }

    @TypeConverter
    fun stringsToJson(strings: List<String?>?): String? = strings?.let { gson.toJson(it, stringsType) }

    @TypeConverter
    fun jsonToStrings(json: String?): List<String?>? = json?.let { gson.fromJson(it, stringsType) }
}
