<section
  class="tl-hero"
  style="--tl-hero-image: url('${content.background_url_s!""}')"
  aria-labelledby="tl-hero-title-${content.id!""}">
  <div class="tl-hero-content">
    <#if content.eyebrow_s?has_content>
      <p class="tl-kicker">${editableText("eyebrow_s", "minimal", "html")?no_esc}</p>
    </#if>
    <h1 id="tl-hero-title-${content.id!""}">${editableText("headline_s", "minimal", "html")?no_esc}</h1>
    <#if content.subtitle_s?has_content>
      <p>${editableText("subtitle_s", "minimal", "html")?no_esc}</p>
    </#if>
    <#if content.primary_label_s?has_content>
      <a class="tl-button" href="${content.primary_url_s!"#destinations"}">${content.primary_label_s}</a>
    </#if>
  </div>
</section>
