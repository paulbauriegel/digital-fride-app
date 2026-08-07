# Third-party licenses

## Grocery icons — OpenMoji

The grocery and category icons in `app/src/main/res/drawable/ic_food_*.xml` and
`ic_category_*.xml` are derived from [OpenMoji](https://openmoji.org), the open-source emoji and
icon project, licensed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

The original SVG artwork was converted to Android VectorDrawable XML. This is a mechanical format
conversion; the artwork itself was not redrawn or recoloured.

## Shelf-life data — USDA FoodKeeper

The storage durations in `app/src/main/assets/shelf_life_rules.json` are derived from the
[USDA FSIS FoodKeeper dataset](https://catalog.data.gov/dataset/fsis-foodkeeper-data)
(dataset version 128, last modified 2018-09-06), a work of the U.S. federal government placed in
the public domain (CC0).

Durations were normalised to whole days and mapped onto this app's food concepts. A small number of
entries carry stricter values than the U.S. source for items where German practice differs; these
are marked `USDA_FOODKEEPER_DE_OVERRIDE` in the data file.

These figures are estimates for planning purposes and are not food-safety advice. The date printed
on the packaging always takes precedence.

## Other dependencies

Runtime library licenses are listed in `app/src/main/assets/licenses/`.
