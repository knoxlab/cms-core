<#assign items = []>
<#if content.items_json_s?has_content>
  <#attempt>
    <#assign items = content.items_json_s?eval_json>
  <#recover>
    <#assign items = []>
  </#attempt>
</#if>
<section class="tl-section" id="destinations" aria-labelledby="tl-destinations-title-${content.id!""}">
  <div class="tl-section-inner">
    <header class="tl-section-head">
      <#if content.section_kicker_s?has_content><p class="tl-kicker">${editableText("section_kicker_s", "minimal", "html")?no_esc}</p></#if>
      <h2 id="tl-destinations-title-${content.id!""}">${editableText("section_title_s", "minimal", "html")?no_esc}</h2>
    </header>
    <div class="tl-destination-grid">
      <#list items as item>
        <article class="tl-destination-card">
          <div class="tl-destination-image">
            <img src="${item.image_url_s!""}" alt="${item.title!"Travel destination"}" loading="lazy">
            <#if item.badge_s?has_content><span class="tl-badge">${item.badge_s}</span></#if>
          </div>
          <div class="tl-destination-copy">
            <#if item.location_s?has_content><p class="tl-location">${item.location_s}</p></#if>
            <h3>${item.title!"Untitled destination"}</h3>
            <#if item.summary_t?has_content><p>${item.summary_t}</p></#if>
            <a class="tl-card-link" href="${item.target_url_s!"#"}">EXPLORE →</a>
          </div>
        </article>
      </#list>
    </div>
    <#if content.button_label_s?has_content>
      <div class="tl-section-footer"><a class="tl-button tl-button-orange" href="#booking">${content.button_label_s}</a></div>
    </#if>
  </div>
</section>
