# killbill-lf-discounts

Kill Bill invoice plugin (`lf-discounts`) that adds Lightfunnels discounts to invoices as `CREDIT_ADJ` lines:

- `LF_DISCOUNT` account custom field (JSON `{type: "fixed"|"percentage", value, expires_at?}`): a percentage applies to
  every invoice, a fixed amount only to full-period base plan invoices.
- `LF_AFFILIATE` account custom field (a percentage): applied after the discount, on what is left.
- Apps (`app_monthly`) are never discounted.

The Lightfunnels backend writes the custom fields; Kill Bill never calls it.

## Build

    docker run --rm -v "$PWD":/src -v "$HOME/.m2":/root/.m2 -w /src maven:3.9-eclipse-temurin-11 mvn -q -B -DskipTests package

## Install

Copy `target/lf-discounts-plugin-<version>.jar` to `<bundles>/plugins/java/lf-discounts/<version>/` and set
`org.killbill.invoice.plugin=lf-discounts`.
