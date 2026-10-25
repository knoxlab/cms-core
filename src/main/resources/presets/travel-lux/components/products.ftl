<#assign items = []>
<#if content.items_json_s?has_content>
  <#attempt>
    <#assign items = content.items_json_s?eval_json>
  <#recover>
    <#assign items = []>
  </#attempt>
</#if>
<section class="tl-section tl-section-alt" id="products" aria-labelledby="tl-products-title-${content.id!""}">
  <div class="tl-section-inner">
    <header class="tl-section-head">
      <#if content.section_kicker_s?has_content><p class="tl-kicker">${editableText("section_kicker_s", "minimal", "html")?no_esc}</p></#if>
      <h2 id="tl-products-title-${content.id!""}">${editableText("section_title_s", "minimal", "html")?no_esc}</h2>
    </header>
    <div class="tl-product-grid">
      <#list items as item>
        <article class="tl-product-card">
          <a class="tl-product-media" href="${item.target_url_s!"#"}">
            <img src="${item.image_url_s!""}" alt="${item.title!"Travel product"}" loading="lazy">
          </a>
          <#if item.badge_s?has_content><span class="tl-badge">${item.badge_s}</span></#if>
          <div class="tl-product-copy">
            <h3>${item.title!"Untitled product"}</h3>
            <#if item.summary_t?has_content><p>${item.summary_t}</p></#if>
            <#if item.price_s?has_content>
              <div class="tl-price">
                <span>$${item.price_s}</span>
                <#if item.old_price_s?has_content><del>$${item.old_price_s}</del></#if>
              </div>
            </#if>
          </div>
        </article>
      </#list>
    </div>
  </div>
</section>
