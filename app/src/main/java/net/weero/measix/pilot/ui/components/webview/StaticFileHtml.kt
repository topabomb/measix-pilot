package net.weero.measix.pilot.ui.components.webview

import net.weero.measix.pilot.service.absoluteStylesheetUrls
import net.weero.measix.pilot.service.renderedInlineUrl

internal const val FILE_PREVIEW_PLACEHOLDER_PATH = "/.measix-preview-placeholder.svg"

/** Preserve static layout; document-owned active content never runs in a file viewer. */
internal fun prepareStaticFileHtml(html: String, origin: String = "https://preview.invalid", imageSizes: Map<Int, Pair<Int, Int>> = emptyMap()): String {
    val document = org.jsoup.Jsoup.parse(html)
    val nonce = java.util.UUID.randomUUID().toString()
    document.select("script,base,iframe,frame,frameset,object,embed,portal").remove()
    document.select("meta[http-equiv]").remove()
    document.select("link").filter { link ->
        link.attr("rel").split(Regex("\\s+")).none { it.equals("stylesheet", true) }
    }.forEach { it.remove() }
    document.select("form").forEach { it.tagName("div"); it.removeAttr("action") }
    document.select("*").forEach { element ->
        element.attributes().asList().filter { it.key.startsWith("on", true) || it.key.startsWith("data-preview-", true) || it.key == "nonce" || it.key in setOf("srcdoc", "autofocus", "autoplay", "formaction") }
            .forEach { element.removeAttr(it.key) }
        listOf("href", "src", "xlink:href", "poster").forEach { name ->
            if (element.attr(name).trim().startsWith("javascript:", true)) element.removeAttr(name)
            if (element.attr(name).trim().startsWith("data:", true)) element.attr(name, renderedInlineUrl(origin, element.attr(name).trim()))
        }
        // A data URL contains commas; retain the ordinary src fallback rather than split srcset incorrectly.
        if (element.attr("srcset").contains("data:", true)) element.removeAttr("srcset")
        if (element.hasAttr("style")) element.attr("style", absoluteStylesheetUrls(element.attr("style"), java.net.URI(""), origin))
    }
    document.select("style").forEach {
        val css = absoluteStylesheetUrls(it.data(), java.net.URI(""), origin)
        it.empty().appendChild(org.jsoup.nodes.DataNode(css))
    }
    document.select("img, picture source").forEach { element ->
        listOf("src", "srcset").forEach { attribute ->
            if (element.hasAttr(attribute)) {
                element.attr("data-preview-" + attribute, element.attr(attribute))
                element.removeAttr(attribute)
            }
        }
        if (element.tagName() == "img") {
            element.attr("decoding", "async").attr("data-preview-pending", "")
        }
    }
    document.select("audio,video").attr("preload", "none").attr("controls", "")
    val restoredRatios = StringBuilder()
    document.select("img").forEachIndexed { index, img ->
        imageSizes[index]?.let { (width, height) ->
            img.attr("src", "$origin$FILE_PREVIEW_PLACEHOLDER_PATH?w=$width&h=$height").removeAttr("data-preview-pending")
            img.attr("data-preview-size", index.toString())
            // Keep layout stable even when the real request replaces an undecoded placeholder.
            // Zero specificity leaves the document's own sizing rules in control.
            restoredRatios.append(":where(img[data-preview-size=\"$index\"]){aspect-ratio:auto $width/$height}")
        }
    }
    // A zero-specificity fallback reserves unknown images without replacing author sizing rules.
    document.head().prependElement("style").appendChild(org.jsoup.nodes.DataNode(
        ":where(img[data-preview-pending]:not([width]):not([height])){min-height:96px}$restoredRatios"))
    if (document.select("meta[name=viewport]").isEmpty()) document.head().appendElement("meta")
        .attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
    document.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content",
        "default-src 'none'; style-src 'unsafe-inline' https: http:; img-src https: http:; font-src https: http:; script-src 'nonce-$nonce'; script-src-attr 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'")
    document.body().appendElement("script").attr("nonce", nonce).appendChild(org.jsoup.nodes.DataNode(
        FILE_PREVIEW_CONTROLLER.replace("PREVIEW_PLACEHOLDER_URL", origin + FILE_PREVIEW_PLACEHOLDER_PATH)))
    return document.outerHtml()
}


// Only this application-owned script runs. All document scripts/events are stripped and CSP is nonce-bound.
private const val FILE_PREVIEW_CONTROLLER = """
(() => {
  const images = Array.from(document.images);
  const placeholders = new WeakMap();
  const sources = img => img.parentElement && img.parentElement.tagName === 'PICTURE'
    ? [...img.parentElement.querySelectorAll('source'), img] : [img];
  const activate = img => {
    if (img.dataset.previewActive === '1') return;
    img.dataset.previewActive = '1';
    sources(img).forEach(node => ['srcset', 'src'].forEach(attr => {
      const value = node.getAttribute('data-preview-' + attr);
      if (value) node.setAttribute(attr, value);
    }));
  };
  const release = img => {
    if (img.dataset.previewActive !== '1') return;
    delete img.dataset.previewActive;
    // Keep a replaced image box and its intrinsic dimensions without retaining the decoded resource.
    const placeholder = placeholders.get(img);
    sources(img).forEach(node => {
      node.removeAttribute('srcset');
      if (node === img && placeholder) node.src = placeholder;
      else node.removeAttribute('src');
    });
  };
  images.forEach((img, index) => {
    if (img.src.startsWith('PREVIEW_PLACEHOLDER_URL?')) placeholders.set(img, img.src);
    img.addEventListener('load', () => {
      if (!img.naturalWidth || img.dataset.previewActive !== '1') return;
      placeholders.set(img, 'PREVIEW_PLACEHOLDER_URL?w=' + img.naturalWidth + '&h=' + img.naturalHeight);
      if (window.MeasixPreviewLayout) window.MeasixPreviewLayout.recordImageSize(index, img.naturalWidth, img.naturalHeight);
      img.removeAttribute('data-preview-pending');
    });
  });
  let observer;
  // Restored intrinsic sizes must be decoded before real requests replace their placeholders.
  // Native scroll restoration then sees the settled boxes, including images without width/height.
  let waitingForLayout = images.some(img => placeholders.has(img)) && document.readyState !== 'complete';
  const watch = () => {
    if (waitingForLayout) return;
    if (observer) observer.disconnect();
    observer = new IntersectionObserver(entries => entries.forEach(entry => {
      if (entry.isIntersecting) activate(entry.target);
      else release(entry.target);
    }), { rootMargin: Math.min(innerHeight, 900) + 'px 0px' });
    images.forEach(img => observer.observe(img));
  };
  if (waitingForLayout) addEventListener('load', () => { waitingForLayout = false; watch(); }, { once: true });
  else watch();
  addEventListener('resize', watch);
})();
"""
