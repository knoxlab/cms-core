<#assign items = []>
<#if content.items_json_s?has_content>
  <#attempt>
    <#assign items = content.items_json_s?eval_json>
  <#recover>
    <#assign items = []>
  </#attempt>
</#if>
<section class="tl-section tl-section-alt" id="shop" aria-labelledby="tl-shop-title-${content.id!""}">
  <div class="tl-section-inner">
    <header class="tl-section-head">
      <#if content.section_kicker_s?has_content><p class="tl-kicker">${editableText("section_kicker_s", "minimal", "html")?no_esc}</p></#if>
      <h2 id="tl-shop-title-${content.id!""}">${editableText("section_title_s", "minimal", "html")?no_esc}</h2>
    </header>
    <div class="tl-mosaic">
      <#list items as item>
        <article class="tl-mosaic-card<#if item?index == 0> is-feature</#if>">
          <a class="tl-mosaic-media" href="${item.target_url_s!"#"}">
            <img src="${item.image_url_s!""}" alt="${item.title!"Travel product"}" loading="lazy">
          </a>
          <#if item.badge_s?has_content><span class="tl-badge">${item.badge_s}</span></#if>
          <div class="tl-mosaic-copy">
            <h3>${item.title!"Untitled product"}</h3>
            <#if item.price_s?has_content>
              <p>$${item.price_s}<#if item.old_price_s?has_content> · <del>$${item.old_price_s}</del></#if></p>
            <#elseif item.summary_t?has_content>
              <p>${item.summary_t}</p>
            </#if>
          </div>
        </article>
      </#list>
    </div>
  </div>
</section>
