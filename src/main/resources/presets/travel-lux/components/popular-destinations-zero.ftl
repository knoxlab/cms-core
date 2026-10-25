<#-- ZERO_CONTENT 示例：按访问量取前 N 条目的地（等价于 clist orderType=hits） -->
<#assign top = items!contentQuery("travel_destination", 3, "viewCount")>
<section class="tl-section tl-section-alt" id="popular">
  <div class="tl-section-inner">
    <header class="tl-section-head">
      <p class="tl-kicker">TRENDING NOW</p>
      <h2>MOST VIEWED DESTINATIONS</h2>
    </header>
    <div class="tl-destination-grid">
      <#list top as item>
        <article class="tl-destination-card">
          <div class="tl-destination-image">
            <img src="${item.image_url_s!""}" alt="${item.title!"Travel destination"}" loading="lazy">
            <#if item.badge_s?has_content><span class="tl-badge">${item.badge_s}</span></#if>
          </div>
          <div class="tl-destination-copy">
            <#if item.location_s?has_content><p class="tl-location">${item.location_s}</p></#if>
            <h3>${item.title!"Untitled destination"}</h3>
            <#if item.summary_t?has_content><p>${item.summary_t}</p></#if>
            <p class="tl-hits">${item.viewCount!0} views</p>
            <a class="tl-card-link" href="${item.target_url_s!"#"}">EXPLORE →</a>
          </div>
        </article>
      </#list>
    </div>
  </div>
</section>
