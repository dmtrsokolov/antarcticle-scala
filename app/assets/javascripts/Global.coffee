unless String::trim then String::trim = ->
  @replace /^\s+|\s+$/g, ""
jQuery(=>
  $(document).ready(=>
    $('.trimmed-input').bind('blur', () ->
      input = $(this)
      input.val(input.val().trim()))
    $('.faded-out').each(() ->
      fadeoutHeight = $(this).prev().height() * 0.8
      $(this).height(fadeoutHeight)
      $(this).css('margin-top', -fadeoutHeight)
    )
  )
)

showSuccessNotification = (text) ->
  showNotification(text, $('.alert-success'))

showFailureNotification = (text) ->
  showNotification(text, $('.alert-danger'))

showNotification = (text, element) ->
  element.find("span").html(text);
  element.fadeIn().delay(3000).fadeOut();

