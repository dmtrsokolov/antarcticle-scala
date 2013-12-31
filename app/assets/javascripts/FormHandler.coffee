# Helper class to ease AJAX validation on forms.
# Every form with, suitable for selector given is processed. Regular submit is replaced
# with AJAX call to form's action URL.
#
# Default selector: class 'default-form'
# Default OK action: redirect to URL from server response body
# Default validation fail action: prepend server response content to the form, i.e. response should contain
# error description and markup. See also formErrors.scala.html template
#
# Selector and actions are customizable, just create your own FormHandler instance with
# all necessary suff set as constructor parameters.

class FormHandler

  defaultOnSuccess: (data) => window.location = data

  defaultOnFail: (data) => this.form.prepend(data.responseText)

  constructor: (@selector, @onSuccess = this.defaultOnSuccess, @onFail = this.defaultOnFail) ->
    this.form = $(@selector)
    this.form.submit( =>
      $('.clear-on-resubmit').remove() # clear old validation messages, of any
      $.post(this.form.attr('action'), this.form.serialize())
        .done((data) => @onSuccess(data))
        .fail((data) => @onFail(data))
      return false
    )

new FormHandler('.default-form')