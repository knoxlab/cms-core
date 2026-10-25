<article class="tl-product-card">
  <a class="tl-product-media" href="${content.target_url_s!"#"}"><img src="${content.image_url_s!""}" alt="${content.title!"Travel product"}" loading="lazy"></a>
  <#if content.badge_s?has_content><span class="tl-badge">${content.badge_s}</span></#if>
  <div class="tl-product-copy">
    <h3>${editableText("title", "minimal", "html")?no_esc}</h3>
    <#if content.summary_t?has_content><p>${editableText("summary_t", "minimal", "html")?no_esc}</p></#if>
    <#if content.price_s?has_content><div class="tl-price"><span>$${content.price_s}</span><#if content.old_price_s?has_content><del>$${content.old_price_s}</del></#if></div></#if>
  </div>
</article>
