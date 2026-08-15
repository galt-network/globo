# Natural Earth data in Globo

How Natural Earth (vector SQLite + 10m rasters) is turned into globe.gl
assets, and what is in / out of the first slice.

## Sources (not served)

| Path | What |
|------|------|
| `data-for-future/packages/natural_earth_vector.sqlite` | NE Vector 5.1.2. Preprocess only. |
| `data-for-future/HYP_HR_SR_OB_DR/` | Hypso+SR source TIFF. Preprocess only. |
| `data-for-future/SR_HR/`, `HYP_HR/`, zips | Unused extras. |

`data/` and `data-for-future/` are gitignored. Live runtime uses `resources/public/natural-earth/` + `data/natural-earth-overlays.json` only.

## Local 10m rasters

All three packs are 21600×10800, WGS84 geographic, TFW
`0.0166667°/px`, origin `−179.9917, 89.9917`, uncompressed TIFF.
Not browser-ready (GeoTIFF, 223–668 MB).

| File | Size | Type | Role |
|------|------|------|------|
| `data-for-future/HYP_HR_SR_OB_DR/HYP_HR_SR_OB_DR.tif` | 668 MB | 8-bit sRGB | Preprocess → 8k WebP. |
| `data-for-future/SR_HR/SR_HR.tif` | 223 MB | 8-bit Gray | Unused. |
| `data-for-future/HYP_HR/HYP_HR.tif` | 668 MB | 8-bit sRGB | Unused. |

Product notes (from [Natural Earth 10m raster](https://www.naturalearthdata.com/downloads/10m-raster-data/)
and [Cross-blended Hypsometric Tints](https://www.naturalearthdata.com/downloads/10m-raster-data/10m-cross-blend-hypso/)):

- `HYP_HR_SR_OB_DR` is the “Shaded Relief, Water, Drainages, and Ocean Bottom”
  variant. Climate-aware elevation colors (Sahara brown, boreal green, ice
  grey) with hillshade, bathymetry, and drainages baked in.
- `SR_HR` is land-only hillshade clipped to the 10m coastline.
- Rasters register with 10m vectors. They are **not** in the SQLite.

### Globe.gl texture vs tiles

- `globeImageUrl` loads **one** full equirectangular image. GPU max texture
  is typically 8192, so native 21600×10800 cannot be used as-is.
- `globeTileEngineUrl` is slippy XYZ for **color only**. It replaces
  `globeImageUrl`. Later path for zoomed detail.
- Native 10m is ~60 px/°. An 8k texture is ~23 px/°.

v1 pairing:

- `globeImageUrl` = preprocessed `HYP_HR_SR_OB_DR` (8k WebP)
- No `bumpImageUrl`. Baked hillshade in the color texture is the relief.

## Vector layers used in v1

Borders (`pathsData`) — land boundary **lines**, not country polygons
(hexholds already owns `polygonsData`):

- `ne_110m_admin_0_boundary_lines_land` (331 LineStrings)
- `ne_50m_admin_0_boundary_lines_land` (390)
- `ne_50m_admin_1_states_provinces_lines` (581) — close zoom only

Names (`labelsData`):

- ADM0: `ne_*m_admin_0_countries` (`NAME`, `LABEL_X`, `LABEL_Y`)
- ADM1: 50m provinces joined to `ne_10m_admin_1_label_points` (lowest scalerank)
- Cities: `ne_50m_populated_places_simple` where `scalerank <= 3` (522)

Do not dump country/province **polygons** into the browser.

## Architecture

Preprocess once → static files under `resources/public/natural-earth/` →
served by the existing assets handler at `<assets-base-url>/natural-earth/…`.

No per-request read of the 810 MB SQLite or the TIFFs.

Auto bands by camera altitude (Earth radii). Layers stack; close zoom
keeps ADM0 borders and hides ADM0 names:

| Altitude | Shown |
|----------|--------|
| ≥ 1.0 | ADM0 110m borders + HTML names |
| 0.35–1.0 | ADM0 50m borders + HTML names |
| < 0.35 | ADM0 50m borders + **10m ADM1** borders/names (viewport) + cities sr≤2 |

Names use globe.gl `htmlElementsData` (CSS2D, constant `px` size, Unicode).
Close-zoom 10m data is **not** dumped as one GeoJSON. Hosts implement
`MapOverlayProvider`; the example loads `data/natural-earth-overlays.json`
and serves `POST <mount>/overlays/query`.

HUD toggles gate each kind. A toggle only appears on the globe when it
is both enabled and in-band.

## Preprocess

```bash
python3 scripts/preprocess_natural_earth.py
```

Requires Pillow. Optional: `--rasters-only` / `--vectors-only`.

Outputs:

| File | From |
|------|------|
| `ne-hyp-sr-ob-dr-8k.webp` | `HYP_HR_SR_OB_DR.tif` → 8192×4096 |
| `ne_110m_admin_0_boundary_lines_land.json` | SQLite WKB → GeoJSON |
| `ne_50m_admin_0_boundary_lines_land.json` | same |
| `ne_110m_admin_0_countries_labels.json` | NAME + LABEL_X/Y |
| `ne_50m_admin_0_countries_labels.json` | same |
| `data/natural-earth-overlays.json` | 10m internal ADM1 + names + cities |

## Runtime (first slice)

- `is.galt.globo.ui.natural-earth` — altitude bands, stacked overlay-view,
  URLs, path/label conversion, toggle gates.
- app-db `:natural-earth {:adm0-borders? true :adm0-names? true
  :adm1-borders? true :adm1-names? true :cities? true :altitude 2.2 :layers {}}`
- HUD Settings: ADM0/ADM1 borders+names, Cities.
- `on-zoom` refreshes NE bands (composed with hexholds viewport refresh).

## Out of slice

- Live TIFF / SQLite in the request path
- `gdal2tiles` / `globeTileEngineUrl`
- 10m overlays or query API
- H3 → ISO_A3 country index
- Disputed-boundary overlay
