<#-- powercms:travel-lux-preset -->
<article class="tl-section">
  <div class="tl-section-inner">
    <p class="tl-kicker">${content.location_s!content.collection_s!"TRAVELLUX"}</p>
    <h1>${content.title!"TravelLux content"}</h1>
    <#if content.image_url_s?has_content>
      <img src="${content.image_url_s}" alt="${content.title!"TravelLux content"}">
    </#if>
    <#if content.summary_t?has_content><p>${content.summary_t}</p></#if>
  </div>
</article>
