package dev.layatest.agent

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import android.util.JsonReader
import java.io.File

/**
 * On-device encoder tokenizer for Laya-Android (mmBERT / Gemma-2 BPE, byte_fallback, vocab 256k).
 *
 * Wraps the Rust HuggingFace `tokenizers` library through DJL, loading the checkpoint's own
 * `tokenizer.json`, so ids are byte-identical to the Python fast tokenizer laya calls
 * (`encode_text(tok, text, add_special_tokens=...)["input_ids"]`). On Android, DJL loads
 * `libdjl_tokenizer.so` from the `ai.djl.android:tokenizer-native` aar via System.loadLibrary.
 *
 * Push `tokenizer.json` to the app's filesDir and pass the File here. A model cut to English (D-058, research/28 E4c)
 * also needs its [vocabMap]: the full tokenizer still splits the text, then each id is renumbered to the kept list,
 * or re-spelt from kept pieces ("▁flipped" -> "▁flip" + "ped", a symbol -> its UTF-8 bytes).
 */
class Tokenizer(tokenizerJson: File, vocabMap: File? = null) : AutoCloseable {

    // Truncation and padding are disabled on purpose. tokenizer.json embeds
    // truncation{max_length:48} (a residue of laya's 48-token option encoding); DJL honours
    // that embedded config by default and would silently truncate every state to 48 tokens.
    // laya's Python state encode does neither -- it truncates/pads itself in build_sequence /
    // collate_items. Disabling both makes DJL byte-identical to transformers (research/07).
    private val tok: HuggingFaceTokenizer =
        HuggingFaceTokenizer.builder()
            .optTokenizerPath(tokenizerJson.toPath())
            .optTruncation(false)
            .optPadding(false)
            .build()

    // the cut: newId[old] is the kept position (-1 if cut), respell[old] the kept ids a cut token is spelt with
    private val newId: IntArray?
    private val respell: Array<IntArray?>?

    init {
        if (vocabMap == null) { newId = null; respell = null } else {
            val ids = IntArray(VOCAB) { -1 }
            val sp = arrayOfNulls<IntArray>(VOCAB)
            // streamed: 206k entries would make a large tree as JSONObject
            JsonReader(vocabMap.bufferedReader()).use { r ->
                r.beginObject()
                while (r.hasNext()) when (r.nextName()) {
                    "kept" -> { r.beginArray(); var n = 0; while (r.hasNext()) ids[r.nextInt()] = n++; r.endArray() }
                    "respell" -> { r.beginObject(); while (r.hasNext()) sp[r.nextName().toInt()] = readInts(r); r.endObject() }
                    else -> r.skipValue()
                }
                r.endObject()
            }
            newId = ids; respell = sp
        }
    }

    /** Same ids as laya's `encode_text(tok, text, add_special_tokens=addSpecialTokens)["input_ids"]`, mapped through the cut if any. */
    fun encode(text: String, addSpecialTokens: Boolean): LongArray {
        val ids = tok.encode(text, addSpecialTokens, /* withOverflowingTokens = */ false).ids
        val map = newId ?: return ids
        fun spelt(id: Long) = respell!![id.toInt()] ?: error("the vocabulary map has no entry for token $id")
        val out = LongArray(ids.sumOf { if (map[it.toInt()] >= 0) 1 else spelt(it).size })
        var k = 0
        for (id in ids) map[id.toInt()].let { n -> if (n >= 0) out[k++] = n.toLong() else for (r in spelt(id)) out[k++] = r.toLong() }
        return out
    }

    private fun readInts(r: JsonReader): IntArray {
        val l = ArrayList<Int>(4)
        r.beginArray(); while (r.hasNext()) l += r.nextInt(); r.endArray()
        return l.toIntArray()
    }

    override fun close() = tok.close()

    companion object {
        // Fixed by the checkpoint (special_tokens_map.json / tokenizer_config.json); DJL 0.33.0
        // exposes no getters for them. Re-derive only if the model's tokenizer changes.
        const val MASK_TOKEN = "<mask>" // laya does serialize_state(state).replace(MASK_TOKEN, " ")
        const val PAD_ID = 0L // collate_items pads input_ids with tok.pad_token_id (also 0 after the cut)
        private const val VOCAB = 256_000

        /** A cut model's id map ships beside it: laya-android-s11-en.nb8-h-e16.onnx -> laya-android-s11-en.vocab-map.json. */
        fun vocabMapFor(model: File) = File(model.parentFile, model.name.substringBefore(".nb8") + ".vocab-map.json").takeIf { it.exists() }
    }
}
