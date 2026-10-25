<#assign items = []>
<#if content.items_json_s?has_content>
  <#attempt>
    <#assign items = content.items_json_s?eval_json>
  <#recover>
    <#assign items = []>
  </#attempt>
</#if>
<section class="tl-section" id="events" aria-labelledby="tl-events-title-${content.id!""}">
  <div class="tl-section-inner">
    <header class="tl-section-head">
      <#if content.section_kicker_s?has_content><p class="tl-kicker">${editableText("section_kicker_s", "minimal", "html")?no_esc}</p></#if>
      <h2 id="tl-events-title-${content.id!""}">${editableText("section_title_s", "minimal", "html")?no_esc}</h2>
    </header>
    <div class="tl-event-grid">
      <#list items as item>
        <article class="tl-event-card">
          <a class="tl-event-media" href="${item.target_url_s!"#"}">
            <img src="${item.image_url_s!""}" alt="${item.title!"Travel event"}" loading="lazy">
          </a>
          <div class="tl-event-date"><strong>${item.date_day_s!"--"}</strong><span>${item.date_month_s!"TBA"}</span></div>
          <div class="tl-event-copy">
            <#if item.location_s?has_content><p class="tl-location">${item.location_s}</p></#if>
            <h3>${item.title!"Untitled event"}</h3>
            <#if item.summary_t?has_content><p>${item.summary_t}</p></#if>
          </div>
        </article>
      </#list>
    </div>
    <#if content.button_label_s?has_content>
      <div class="tl-section-footer"><a class="tl-button tl-button-orange" href="#booking">${content.button_label_s}</a></div>
    </#if>
  </div>
</section>
