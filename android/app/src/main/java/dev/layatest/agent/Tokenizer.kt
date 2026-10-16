package dev.layatest.agent

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import java.io.File

/**
 * On-device encoder tokenizer for Laya-Android (mmBERT / Gemma-2 BPE, byte_fallback, vocab 256k).
 *
 * Wraps the Rust HuggingFace `tokenizers` library through DJL, loading the checkpoint's own
 * `tokenizer.json`, so ids are byte-identical to the Python fast tokenizer laya calls
 * (`encode_text(tok, text, add_special_tokens=...)["input_ids"]`). On Android, DJL loads
 * `libdjl_tokenizer.so` from the `ai.djl.android:tokenizer-native` aar via System.loadLibrary.
 *
 * Push `tokenizer.json` to the app's filesDir and pass the File here.
 */
class Tokenizer(tokenizerJson: File) : AutoCloseable {

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

    /** Same ids as laya's `encode_text(tok, text, add_special_tokens=addSpecialTokens)["input_ids"]`. */
    fun encode(text: String, addSpecialTokens: Boolean): LongArray =
        tok.encode(text, addSpecialTokens, /* withOverflowingTokens = */ false).ids

    override fun close() = tok.close()

    companion object {
        // Fixed by the checkpoint (special_tokens_map.json / tokenizer_config.json); DJL 0.33.0
        // exposes no getters for them. Re-derive only if the model's tokenizer changes.
        const val MASK_TOKEN = "<mask>" // laya does serialize_state(state).replace(MASK_TOKEN, " ")
        const val PAD_ID = 0L // collate_items pads input_ids with tok.pad_token_id
    }
}
