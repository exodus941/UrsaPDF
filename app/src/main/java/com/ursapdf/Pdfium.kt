package com.ursapdf

/**
 * The downloaded engine's entry points (engine/ursa.c). Only callable after
 * [Libraries.load] succeeds. PDFium is not thread-safe: call from one thread.
 */
object Pdfium {
    /** The engine interface this app speaks; must equal the downloaded engine's. */
    const val API = 1

    // Pdfium field types (FPDF_FORMFIELD_*).
    const val PUSHBUTTON = 1
    const val CHECKBOX = 2
    const val RADIOBUTTON = 3
    const val COMBOBOX = 4
    const val LISTBOX = 5
    const val TEXTFIELD = 6
    const val SIGNATURE = 7

    @JvmStatic external fun api(): Int
    @JvmStatic external fun init(pdfiumPath: String): String?
    @JvmStatic external fun open(fd: Int): Long
    @JvmStatic external fun close(h: Long)
    @JvmStatic external fun formType(h: Long): Int
    @JvmStatic external fun search(h: Long, page: Int, query: String): FloatArray
    @JvmStatic external fun widgets(h: Long, page: Int): String
    @JvmStatic external fun setText(h: Long, page: Int, annot: Int, text: String): Boolean
    @JvmStatic external fun click(h: Long, page: Int, annot: Int): Boolean
    @JvmStatic external fun choose(h: Long, page: Int, annot: Int, option: Int): Boolean
    @JvmStatic external fun save(h: Long, fd: Int): Boolean
}
