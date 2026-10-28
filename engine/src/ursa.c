// UrsaPDF engine glue: search and AcroForm editing through PDFium, for phones whose
// built-in PdfRenderer lacks them (before Android 15 / SDK extension 13).
//
// This file and libpdfium.so ship together as the downloaded engine, never inside the
// APK. PDFium is opened with dlopen from the app's private folder, so this library has
// no link-time dependency on it; every PDFium call goes through a pointer from dlsym.
//
// Coordinates returned to Kotlin are page points with a top-left origin, matching
// the built-in PdfRenderer. Strings cross as UTF-16, PDFium's own wide-string form.

#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "fpdf_annot.h"
#include "fpdf_formfill.h"
#include "fpdf_save.h"
#include "fpdf_text.h"
#include "fpdfview.h"

// Bump when a function below changes shape; the app accepts one API level.
#define ENGINE_API 1

#define FNS(X) \
  X(FPDF_InitLibrary) X(FPDF_LoadCustomDocument) X(FPDF_CloseDocument) X(FPDF_GetPageCount) \
  X(FPDF_LoadPage) X(FPDF_ClosePage) X(FPDF_GetPageHeightF) X(FPDF_GetFormType) \
  X(FPDFDOC_InitFormFillEnvironment) X(FPDFDOC_ExitFormFillEnvironment) \
  X(FORM_OnAfterLoadPage) X(FORM_OnBeforeClosePage) X(FORM_SetFocusedAnnot) \
  X(FORM_SelectAllText) X(FORM_ReplaceSelection) X(FORM_ForceToKillFocus) \
  X(FORM_SetIndexSelected) X(FORM_OnLButtonDown) X(FORM_OnLButtonUp) \
  X(FPDFPage_GetAnnotCount) X(FPDFPage_GetAnnot) X(FPDFPage_CloseAnnot) \
  X(FPDFAnnot_GetSubtype) X(FPDFAnnot_GetRect) X(FPDFAnnot_GetFormFieldType) \
  X(FPDFAnnot_GetFormFieldFlags) X(FPDFAnnot_GetFormFieldValue) \
  X(FPDFAnnot_GetFormFieldAlternateName) X(FPDFAnnot_GetFormFieldName) \
  X(FPDFAnnot_IsChecked) X(FPDFAnnot_GetOptionCount) X(FPDFAnnot_GetOptionLabel) \
  X(FPDFAnnot_IsOptionSelected) X(FPDF_SaveAsCopy) \
  X(FPDFText_LoadPage) X(FPDFText_ClosePage) X(FPDFText_FindStart) X(FPDFText_FindNext) \
  X(FPDFText_FindClose) X(FPDFText_GetSchResultIndex) X(FPDFText_GetSchCount) \
  X(FPDFText_CountRects) X(FPDFText_GetRect)

#define DECL(n) static __typeof__(&n) p_##n;
FNS(DECL)

typedef struct {
  FPDF_DOCUMENT doc;
  FPDF_FORMHANDLE form;
  FPDF_FORMFILLINFO info;
  int fd;
} Doc;

static int readBlock(void* param, unsigned long pos, unsigned char* buf, unsigned long size) {
  int fd = (int)(intptr_t)param;
  unsigned long done = 0;
  while (done < size) {
    ssize_t n = pread(fd, buf + done, size - done, (off_t)(pos + done));
    if (n <= 0) return 0;
    done += (unsigned long)n;
  }
  return 1;
}

typedef struct { FPDF_FILEWRITE w; int fd; int ok; } Writer;

static int writeBlock(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
  Writer* w = (Writer*)self;
  const char* p = data;
  while (size > 0) {
    ssize_t n = write(w->fd, p, size);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) { w->ok = 0; return 0; }
    p += n; size -= (unsigned long)n;
  }
  return 1;
}

// ---- small JSON writer for the widget list ----

typedef struct { char* s; size_t n, cap; } Buf;

static void put(Buf* b, const char* t, size_t len) {
  if (b->n + len + 1 > b->cap) {
    b->cap = (b->n + len + 1) * 2;
    b->s = realloc(b->s, b->cap);
  }
  memcpy(b->s + b->n, t, len);
  b->n += len;
  b->s[b->n] = 0;
}
static void puts_(Buf* b, const char* t) { put(b, t, strlen(t)); }
static void putf(Buf* b, double v) { char t[32]; int k = snprintf(t, sizeof t, "%.3f", v); put(b, t, (size_t)k); }
static void puti(Buf* b, long v) { char t[24]; int k = snprintf(t, sizeof t, "%ld", v); put(b, t, (size_t)k); }

// UTF-16LE from PDFium to a JSON string literal (as \uXXXX escapes, so no UTF-8 work).
static void putw(Buf* b, const unsigned short* w, unsigned long bytes) {
  puts_(b, "\"");
  unsigned long n = bytes / 2;
  for (unsigned long i = 0; i < n && w[i]; i++) {
    unsigned short c = w[i];
    if (c >= 0x20 && c < 0x7f && c != '"' && c != '\\') { char ch = (char)c; put(b, &ch, 1); }
    else { char t[8]; snprintf(t, sizeof t, "\\u%04x", c); put(b, t, 6); }
  }
  puts_(b, "\"");
}

// Calls a PDFium "write UTF-16 into buffer, return byte length" function twice.
#define WIDE(b, call_with_buf_len)                                       \
  do {                                                                   \
    unsigned long need_ = 0;                                             \
    { unsigned short* buf = NULL; unsigned long len = 0; need_ = call_with_buf_len; } \
    unsigned short* buf = need_ ? malloc(need_) : NULL;                  \
    unsigned long len = need_;                                           \
    if (buf) call_with_buf_len;                                          \
    putw(b, buf, buf ? need_ : 0);                                       \
    free(buf);                                                           \
  } while (0)

// ---- JNI ----

static jstring str(JNIEnv* env, const char* s) { return (*env)->NewStringUTF(env, s); }

JNIEXPORT jint JNICALL Java_com_ursapdf_Pdfium_api(JNIEnv* env, jclass c) { return ENGINE_API; }

/** Loads PDFium from [path]; returns null on success, else a reason. */
JNIEXPORT jstring JNICALL Java_com_ursapdf_Pdfium_init(JNIEnv* env, jclass c, jstring path) {
  const char* p = (*env)->GetStringUTFChars(env, path, NULL);
  void* h = dlopen(p, RTLD_NOW | RTLD_LOCAL);
  (*env)->ReleaseStringUTFChars(env, path, p);
  if (!h) return str(env, dlerror());
#define LOAD(n) p_##n = (__typeof__(&n))dlsym(h, #n); if (!p_##n) return str(env, "missing " #n);
  FNS(LOAD)
  p_FPDF_InitLibrary();
  return NULL;
}

/** Opens the PDF on [fd] (the engine keeps the fd). Returns a handle, or 0. */
JNIEXPORT jlong JNICALL Java_com_ursapdf_Pdfium_open(JNIEnv* env, jclass c, jint fd) {
  struct stat st;
  if (fstat(fd, &st) != 0) return 0;
  FPDF_FILEACCESS* fa = calloc(1, sizeof *fa);
  fa->m_FileLen = (unsigned long)st.st_size;
  fa->m_GetBlock = readBlock;
  fa->m_Param = (void*)(intptr_t)fd;
  FPDF_DOCUMENT doc = p_FPDF_LoadCustomDocument(fa, NULL);
  if (!doc) { free(fa); return 0; }
  Doc* d = calloc(1, sizeof *d);
  d->doc = doc;
  d->fd = fd;
  d->info.version = 1;
  d->form = p_FPDFDOC_InitFormFillEnvironment(doc, &d->info);
  return (jlong)(intptr_t)d;
}

JNIEXPORT void JNICALL Java_com_ursapdf_Pdfium_close(JNIEnv* env, jclass c, jlong h) {
  Doc* d = (Doc*)(intptr_t)h;
  if (!d) return;
  if (d->form) p_FPDFDOC_ExitFormFillEnvironment(d->form);
  p_FPDF_CloseDocument(d->doc);
  close(d->fd);
  free(d);
}

JNIEXPORT jint JNICALL Java_com_ursapdf_Pdfium_formType(JNIEnv* env, jclass c, jlong h) {
  return p_FPDF_GetFormType(((Doc*)(intptr_t)h)->doc);
}

/** Matches of [q] on page [i]: [count, then per match: rects, l, t, r, b, ...]. */
JNIEXPORT jfloatArray JNICALL Java_com_ursapdf_Pdfium_search(JNIEnv* env, jclass c, jlong h, jint i, jstring q) {
  Doc* d = (Doc*)(intptr_t)h;
  FPDF_PAGE page = p_FPDF_LoadPage(d->doc, i);
  if (!page) return (*env)->NewFloatArray(env, 0);
  float ph = p_FPDF_GetPageHeightF(page);
  FPDF_TEXTPAGE tp = p_FPDFText_LoadPage(page);
  const jchar* w = (*env)->GetStringChars(env, q, NULL);
  jsize wl = (*env)->GetStringLength(env, q);
  unsigned short* wq = calloc((size_t)wl + 1, 2);
  memcpy(wq, w, (size_t)wl * 2);
  (*env)->ReleaseStringChars(env, q, w);

  float* out = malloc(sizeof(float) * 64);
  size_t n = 1, cap = 64;
  int matches = 0;
  FPDF_SCHHANDLE sh = tp ? p_FPDFText_FindStart(tp, wq, 0, 0) : NULL;
  while (sh && p_FPDFText_FindNext(sh)) {
    int at = p_FPDFText_GetSchResultIndex(sh), cnt = p_FPDFText_GetSchCount(sh);
    int rects = p_FPDFText_CountRects(tp, at, cnt);
    if (n + 1 + (size_t)rects * 4 > cap) { cap = (n + 1 + (size_t)rects * 4) * 2; out = realloc(out, sizeof(float) * cap); }
    out[n++] = (float)rects;
    for (int k = 0; k < rects; k++) {
      double l, t, r, b;
      p_FPDFText_GetRect(tp, k, &l, &t, &r, &b);
      out[n++] = (float)l; out[n++] = ph - (float)t; out[n++] = (float)r; out[n++] = ph - (float)b;
    }
    matches++;
  }
  out[0] = (float)matches;
  if (sh) p_FPDFText_FindClose(sh);
  if (tp) p_FPDFText_ClosePage(tp);
  p_FPDF_ClosePage(page);
  free(wq);
  jfloatArray arr = (*env)->NewFloatArray(env, (jsize)n);
  (*env)->SetFloatArrayRegion(env, arr, 0, (jsize)n, out);
  free(out);
  return arr;
}

static FPDF_PAGE openFormPage(Doc* d, int i) {
  FPDF_PAGE page = p_FPDF_LoadPage(d->doc, i);
  if (page && d->form) p_FORM_OnAfterLoadPage(page, d->form);
  return page;
}

static void closeFormPage(Doc* d, FPDF_PAGE page) {
  if (d->form) p_FORM_OnBeforeClosePage(page, d->form);
  p_FPDF_ClosePage(page);
}

/** The page's form widgets as a JSON array. */
JNIEXPORT jstring JNICALL Java_com_ursapdf_Pdfium_widgets(JNIEnv* env, jclass c, jlong h, jint i) {
  Doc* d = (Doc*)(intptr_t)h;
  FPDF_PAGE page = openFormPage(d, i);
  if (!page) return str(env, "[]");
  float ph = p_FPDF_GetPageHeightF(page);
  Buf b = {0};
  puts_(&b, "[");
  int count = p_FPDFPage_GetAnnotCount(page), first = 1;
  for (int k = 0; k < count; k++) {
    FPDF_ANNOTATION a = p_FPDFPage_GetAnnot(page, k);
    if (!a) continue;
    if (p_FPDFAnnot_GetSubtype(a) != FPDF_ANNOT_WIDGET) { p_FPDFPage_CloseAnnot(a); continue; }
    FS_RECTF r;
    p_FPDFAnnot_GetRect(a, &r);
    if (!first) puts_(&b, ",");
    first = 0;
    puts_(&b, "{\"i\":"); puti(&b, k);
    puts_(&b, ",\"type\":"); puti(&b, p_FPDFAnnot_GetFormFieldType(d->form, a));
    puts_(&b, ",\"flags\":"); puti(&b, p_FPDFAnnot_GetFormFieldFlags(d->form, a));
    puts_(&b, ",\"l\":"); putf(&b, r.left);
    puts_(&b, ",\"t\":"); putf(&b, ph - r.top);
    puts_(&b, ",\"r\":"); putf(&b, r.right);
    puts_(&b, ",\"b\":"); putf(&b, ph - r.bottom);
    puts_(&b, ",\"checked\":"); puts_(&b, p_FPDFAnnot_IsChecked(d->form, a) ? "true" : "false");
    puts_(&b, ",\"value\":");
    WIDE(&b, p_FPDFAnnot_GetFormFieldValue(d->form, a, buf, len));
    puts_(&b, ",\"label\":");
    WIDE(&b, p_FPDFAnnot_GetFormFieldAlternateName(d->form, a, buf, len));
    puts_(&b, ",\"name\":");
    WIDE(&b, p_FPDFAnnot_GetFormFieldName(d->form, a, buf, len));
    puts_(&b, ",\"options\":[");
    int opts = p_FPDFAnnot_GetOptionCount(d->form, a);
    for (int o = 0; o < opts; o++) {
      if (o) puts_(&b, ",");
      puts_(&b, "{\"label\":");
      WIDE(&b, p_FPDFAnnot_GetOptionLabel(d->form, a, o, buf, len));
      puts_(&b, ",\"on\":");
      puts_(&b, p_FPDFAnnot_IsOptionSelected(d->form, a, o) ? "true}" : "false}");
    }
    puts_(&b, "]}");
    p_FPDFPage_CloseAnnot(a);
  }
  puts_(&b, "]");
  closeFormPage(d, page);
  jstring s = str(env, b.s);
  free(b.s);
  return s;
}

static FPDF_ANNOTATION focus(Doc* d, FPDF_PAGE page, int k) {
  FPDF_ANNOTATION a = p_FPDFPage_GetAnnot(page, k);
  if (a) p_FORM_SetFocusedAnnot(d->form, a);
  return a;
}

JNIEXPORT jboolean JNICALL Java_com_ursapdf_Pdfium_setText(JNIEnv* env, jclass c, jlong h, jint i, jint k, jstring text) {
  Doc* d = (Doc*)(intptr_t)h;
  FPDF_PAGE page = openFormPage(d, i);
  if (!page) return JNI_FALSE;
  FPDF_ANNOTATION a = focus(d, page, k);
  if (a) {
    const jchar* w = (*env)->GetStringChars(env, text, NULL);
    jsize wl = (*env)->GetStringLength(env, text);
    unsigned short* wt = calloc((size_t)wl + 1, 2);
    memcpy(wt, w, (size_t)wl * 2);
    (*env)->ReleaseStringChars(env, text, w);
    p_FORM_SelectAllText(d->form, page);
    p_FORM_ReplaceSelection(d->form, page, wt);
    free(wt);
    p_FORM_ForceToKillFocus(d->form);
    p_FPDFPage_CloseAnnot(a);
  }
  closeFormPage(d, page);
  return a ? JNI_TRUE : JNI_FALSE;
}

/** Click the widget's centre: toggles a checkbox, picks a radio button. */
JNIEXPORT jboolean JNICALL Java_com_ursapdf_Pdfium_click(JNIEnv* env, jclass c, jlong h, jint i, jint k) {
  Doc* d = (Doc*)(intptr_t)h;
  FPDF_PAGE page = openFormPage(d, i);
  if (!page) return JNI_FALSE;
  FPDF_ANNOTATION a = p_FPDFPage_GetAnnot(page, k);
  if (a) {
    FS_RECTF r;
    p_FPDFAnnot_GetRect(a, &r);
    double x = (r.left + r.right) / 2, y = (r.top + r.bottom) / 2;
    p_FORM_OnLButtonDown(d->form, page, 0, x, y);
    p_FORM_OnLButtonUp(d->form, page, 0, x, y);
    p_FORM_ForceToKillFocus(d->form);
    p_FPDFPage_CloseAnnot(a);
  }
  closeFormPage(d, page);
  return a ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_ursapdf_Pdfium_choose(JNIEnv* env, jclass c, jlong h, jint i, jint k, jint option) {
  Doc* d = (Doc*)(intptr_t)h;
  FPDF_PAGE page = openFormPage(d, i);
  if (!page) return JNI_FALSE;
  FPDF_ANNOTATION a = focus(d, page, k);
  FPDF_BOOL ok = a ? p_FORM_SetIndexSelected(d->form, page, option, 1) : 0;
  p_FORM_ForceToKillFocus(d->form);
  if (a) p_FPDFPage_CloseAnnot(a);
  closeFormPage(d, page);
  return ok ? JNI_TRUE : JNI_FALSE;
}

/** Writes the edited document to [fd] (the caller closes it). */
JNIEXPORT jboolean JNICALL Java_com_ursapdf_Pdfium_save(JNIEnv* env, jclass c, jlong h, jint fd) {
  Doc* d = (Doc*)(intptr_t)h;
  Writer w = {{1, writeBlock}, fd, 1};
  FPDF_BOOL ok = p_FPDF_SaveAsCopy(d->doc, &w.w, 0);
  return ok && w.ok ? JNI_TRUE : JNI_FALSE;
}
