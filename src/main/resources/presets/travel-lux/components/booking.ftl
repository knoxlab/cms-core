<section class="tl-booking-wrap" id="booking" aria-labelledby="tl-booking-title-${content.id!""}">
  <div class="tl-booking">
    <h2 class="tl-booking-title" id="tl-booking-title-${content.id!""}">${editableText("section_title_s", "minimal", "html")?no_esc}</h2>
    <div class="tl-booking-fields">
      <div class="tl-booking-field"><span aria-hidden="true">⌖</span><span>${editableText("destination_label_s", "minimal", "html")?no_esc}</span></div>
      <div class="tl-booking-field"><span aria-hidden="true">□</span><span>${editableText("date_label_s", "minimal", "html")?no_esc}</span></div>
      <div class="tl-booking-field"><span aria-hidden="true">♙</span><span>${editableText("guest_label_s", "minimal", "html")?no_esc}</span></div>
      <button class="tl-button tl-button-orange" type="button">${content.button_label_s!"BOOK NOW"}</button>
    </div>
  </div>
</section>
