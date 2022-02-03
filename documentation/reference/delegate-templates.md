# Delegate templates

Delegate templates allow you to write multiple implementations of a template and
choose one of them at render time. Delegate templates are defined and called
using `deltemplate` and `delcall`, which have syntax similar to `template` and
`call`.

There are two independent ways to use delegates, differing in how you control
which delegate implementation is called. Delegates with delegate packages
(`delpackage`) are appropriate for cases where you don't intend to send code for
unused delegate implementations to the client (for example, an experiment whose
code is only sent to a small subset of users.) Delegates with the `variant`
attribute are appropriate for finer control of delegate selection at the call
site.

[TOC]

## Delegate templates (with delpackage)

Delegates with delegate packages (`delpackage`) are appropriate for cases where
you don't intend to send code for unused delegate implementations to the client
(for example, an experiment whose code is only sent to a small subset of users.)

`main.soy` syntax:

```soy
{namespace ...}

/** Caller (basic template, not delegate template). */
{template aTemplate}
  {delcall aaa.bbb.myButton allowemptydefault="true" data="..." /}
{/template}

/** Default implementation. */
{deltemplate aaa.bbb.myButton}
  ...
{/deltemplate}
```

`experiment.soy` syntax:

```soy
{delpackage MyExperiment}
{namespace ...}

/** My experiment's implementation. */
{deltemplate aaa.bbb.myButton}
  ...
{/deltemplate}
```

The implementations must appear in different files, and each file other than the
default implementation must declare a delegate package name (`delpackage`). This
is the identifier used to select the implementation at usage time.

The delegate template names are not within the file's namespace; namespaces only
apply to basic templates. Instead, delegate template names are just strings that
are always written in full. They can be any identifier or multiple identifiers
connected with dots. The namespace of any delegate template file, however, must
be different from the default file and any other included delegate template
file.

Template files can have an optional `delpackage` declaration at the top, just
above the `namespace` declaration. And multiple files can have the same
`delpackage` name, putting them all within the same delegate package. If a
delegate template is defined in a file without `delpackage`, then it is a
default implementation. If a delegate template is defined in a file with
`delpackage`, then it is a non-default implementation.

At render time, when a delegate call needs to be resolved, Soy looks at all the
"active" implementations of the delegate template and uses the implementation
with the highest priority. Basically, this means:

1.  Use a non-default implementation, if there is one.
2.  Otherwise, use the default implementation, if there is one.
3.  Otherwise, if the `delcall` has the attribute `allowemptydefault="true"`,
    then the call renders to the empty string.
4.  Otherwise, an error occurs.

Due to the third case, it is legal to render a delegate template that has no
default implementation, as long as the `delcall` has `allowemptydefault="true"`.

What counts as an "active" implementation depends on the backend in use. In
JavaScript, an active implementation is simply an implementation that is defined
in the JavaScript files that are loaded. Ship only the generated JavaScript
files for the active `delpackage`s.

In Java, use `SoySauce.Renderer#setActiveDelegatePackageSelector` to set the
active implementations. For example, with the example template code above, call

```java
soySauce.newRenderer(...)
    .setActiveDelegatePackageSelector("MyExperiment"::equals)
    .setData(...)
    .render()
```

This will use the non-default implementation of `aaa.bbb.myButton` from the
`MyExperiment` delegate package. On the other hand, if you omit the call to
`setActiveDelegatePackageSelector()`, or if you pass a set not including
`MyExperiment`, it will use the default implementation of `aaa.bbb.myButton`.

In either backend, it is an error to have more than one active implementation at
the same priority (for example, multiple active non-default implementations).

### Special case: a modded Soy template B under another modded Soy template A

Please note that it is an error for two deltemplates to be installed at runtime
with the same priority. Therefore, do not define the default implementation of
deltemplate B within a delpackage. This would give B's default implementation
the same priority as B's non-default (delpackage) implementations; essentially,
B would not have a default implementation.

So instead, put deltemplate B into a file without a delpackage. This will allow
the variant (with a delpackage) to override it.

## Delegate Templates (with variant)

Delegates with the `variant` attribute are appropriate for finer control of
delegate selection at the call site.

Syntax:

```soy
/** Caller (basic template, not delegate template). */
{template aTemplate}
  {delcall aaa.bbb.myButton variant="$variantToUse" /}
{/template}

/** Implementation 'alpha'. */
{deltemplate aaa.bbb.myButton variant="'alpha'"}
  ...
{/deltemplate}

/** Implementation 'beta'. */
{deltemplate aaa.bbb.myButton variant="'beta'"}
  ...
{/deltemplate}
```

The variant in a `deltemplate` command must be a string literal containing an
identifier. If no variant is specified, then it defaults to the empty string.
The variant in a `delcall` command can be an arbitrary expression, as long as it
evaluates to a string at render time.

At render time, when a delegate call needs to be resolved,

1.  Use the delegate implementation with matching variant, if there is one.
2.  Otherwise, use the delegate implementation with no variant, if there is one.
3.  Otherwise, an error occurs.

### Boq Web Users: Including CSS for deltemplates with variants

WARNING: If you are a Boq Web user, and your UI node uses
`InitialCssLoadingStrategy.ROOT` or `InitialCssLoadingStrategy.COLLECT`, Boq Web
by default will **not** serve css for deltemplates with variants. See below for
how to include this CSS.

To figure out which [`CSS Strategy`](http://cs/f:InitialCssLoadingStrategy.java)
your UI node uses, look in your `ui/BoqletModule.java` for a line like:

```java
install(InitialCssStrategyModule.forInstance(getNodeConfig(), InitialCssLoadingStrategy.COLLECT));
```

If you do **not** see a see a line like the one above in your BoqletModule.java,
then your app is using `InitialCssLoadingStrategy.DEFAULT`.

For an overview of the different CSS serving strategies, see
[Boq Web CSS Strategy Options](go/boq-css?#boq-web-css-strategy-options).

#### If your UI node uses `InitialCssLoadingStrategy.DEFAULT`:

CSS is typically already included for your deltemplates with variants.
`InitialCssLoadingStrategy.DEFAULT` uses the modules from the
`boq_initial_css_modules` build rule (in your `ui/BUILD`). This build rule uses
`af_soy_library` targets (and their transitive deps) to construct one CSS module
for each page in your app. Typically users' deltemplates are already
transitively included somewhere in the Soy dep tree of the
`boq_initial_css_modules` map entry for the action being rendered.

However, if your deltemplate lives in its own `af_soy_library` and the CSS is
somehow not being included (see go/boq-web-troubleshoot-css for help
troubleshooting), then you may need to explicitly add the deltemplate's lib to
the `boq_initial_css_modules` map in your `ui/BUILD` file.

#### If your UI node uses `InitialCssLoadingStrategy.ROOT`:

Boq Web by default will **not** serve any CSS for deltemplates with variants.

In order to include CSS for a deltemplate that has a `variant=`, you can call
the [`dynamicCssSingle`](http://cs/f:css.soy%20symbol:dynamicCssSingle) Soy
template, and pass in the name of the deltemplate and variant, like this:

```soy
import {dynamicCssSingle} from 'java/com/google/apps/framework/template/css/css.soy';

{deltemplate boq.shopping.property.ui.components.productcard.templates.content variant="'DEFAULT'"}
  {call dynamicCssSingle}
    {param delTemplate: 'boq.shopping.property.ui.components.productcard.templates.content' /}
    {param variant: 'DEFAULT' /}
  {/call}

  {call someOtherTemplate /}

{/deltemplate}
```

This will fetch all of the transitive CSS needed for that deltemplate, and
render it in a `<style>` tag.

**If you want to emit one style tag** for multiple deltemplates and/or variants,
you can use [`dynamicCssList`](http://cs/f:css.soy%20symbol:dynamicCssList) or
[`dynamicCssFull`](http://cs/f:css.soy%20symbol:dynamicCssFull).

#### If your UI node uses `InitialCssLoadingStrategy.COLLECT`:

Boq Web by default will **not** serve any CSS for deltemplates with variants.

In order to include CSS for a deltemplate that has a `variant=`, you need to
manually call the `collectCss('some.template')` Soy function for any templates
that the deltemplate renders, and insert the result into a `<style>` tag, like
this:

```soy
{namespace my.ns}

import {style} from 'java/com/google/apps/framework/template/css.soy';

{template someTemplate}
  ...
{/template}

{template anotherTemplate}
  ...
{/template}

{deltemplate boq.shopping.property.ui.components.productcard.templates.content variant="'2'"}
  {@param productCard: ProductCard}

  {call style}
    {param css:
        collectCss('my.ns.someTemplate') /}
  {/call}
  {call style}
    {param css:
        collectCss('my.ns.anotherTemplate') /}
  {/call}

  {call someTemplate data="all" /}
  {call anotherTemplate data="all" /}
{/deltemplate}
```

#### Why does the framework not ship delvariant css for `ROOT` and `COLLECT` modes?

When `InitialCssLoadingStrategy.ROOT` or `InitialCssLoadingStrategy.COLLECT` is
enabled, Boq Web determines what CSS to ship for your page **at serve time**
based on which templates were rendered.

Most styles for your initial page are shipped in the HTML header, which happens
before any of your Soy templates are rendered. However, **since variants are
based on data**, they can't be resolved until Soy is partway through rendering
your HTML (and has finished resolving the relevant data fetches). So, the CSS
for deltemplates with variants cannot be included in the HTML header, and needs
to be manually pulled in later, once we know which variants will actually be
rendered.

Note that this is different than regular deltemplates *with mods* (i.e. a
`delpackage`), where we know which mods are enabled right away at serve-time and
can ship the corresponding styles in the HTML header.
