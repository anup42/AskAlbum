# Samsung Gallery APK asset/resource inventory

This is a read-only inventory of resource names observed in the Samsung Gallery APK installed on the connected reference device. The APK and its binary assets are not stored in this repository and must not be copied into Agentic Gallery.

## Summary

- APK size observed: 59,341,990 bytes
- ZIP entries: 4,100
- Loose `assets/` entries: 82
- Compiled resource counts observed:
  - drawables: 2,026
  - mipmaps: 3
  - colors: 2,062
  - dimensions: 3,752
  - styles: 1,169
  - strings: 2,847
  - layouts: 1,017
  - animations: 272
  - arrays: 80

Most of Samsung Gallery's One UI appearance is implemented through compiled Android resources, Samsung framework components, and runtime behavior rather than reusable loose assets. The useful product reference is therefore the layout and interaction pattern captured in the screenshots, not copying the files below.

## All loose asset paths

```text
assets/AZURE2d.png
assets/BLUE2d.png
assets/CYAN2d.png
assets/GREEN2d.png
assets/MAGENTAV2d.png
assets/ORANGE2d.png
assets/RED2d.png
assets/ROSE2d.png
assets/VIOLET2d.png
assets/YELLOW2d.png
assets/defaultAlbumImage.jpg
assets/dexopt/baseline.prof
assets/dexopt/baseline.profm
assets/infowindow_bg2d.9.png
assets/location_map_gps_3d.png
assets/location_map_gps_locked.png
assets/location_pressed2d.png
assets/location_selected2d.png
assets/location_unselected2d.png
assets/maps_dav_compass_needle_large2d.png
assets/marker_default2d.png
assets/marker_gps_no_sharing2d.png
assets/zoomin_pressed2d.png
assets/zoomin_selected2d.png
assets/zoomin_unselected2d.png
assets/zoomout_pressed2d.png
assets/zoomout_selected2d.png
assets/zoomout_unselected2d.png
assets/NOTICE.txt
assets/animation/centerspace-l2r.json
assets/animation/centerspace-r2l.json
assets/animation/dollyzoom-l.json
assets/animation/dollyzoom-r.json
assets/animation/glidespace-l2r.json
assets/animation/glidespace-r2l.json
assets/animation/landscape.json
assets/animation/slide.json
assets/animation/static.json
assets/ap12d.data
assets/ap2d.data
assets/app_functions.xml
assets/app_functions_schema.xsd
assets/app_functions_v2.xml
assets/audio_eraser_looping.json
assets/film_icon.json
assets/img_resolution_test.json
assets/intelligence_progress_color.json
assets/lottie_arrow_left.json
assets/lottie_arrow_right.json
assets/lottie_gallery_ico_document_scan.json
assets/lottie_gallery_story_swipe_up_arrow.json
assets/lottie_ico_gallery_suggestions_clean_out.json
assets/lottie_ico_gallery_suggestions_fix_up.json
assets/org/apache/commons/math3/exception/util/LocalizedFormats_fr.properties
assets/org/apache/commons/math3/random/new-joe-kuo-6.1000
assets/panel_handler.json
assets/recap_monthly_brief.json
assets/recap_quarterly_brief.json
assets/recap_yearly_brief.json
assets/recap_yearly_moments.json
assets/recap_yearly_people.json
assets/recap_yearly_places.json
assets/shaders/fragment_shader_alpha_scale_es2.glsl
assets/shaders/fragment_shader_copy_es2.glsl
assets/shaders/fragment_shader_hsl_es2.glsl
assets/shaders/fragment_shader_lut_es2.glsl
assets/shaders/fragment_shader_oetf_es3.glsl
assets/shaders/fragment_shader_separable_convolution_es2.glsl
assets/shaders/fragment_shader_transformation_es2.glsl
assets/shaders/fragment_shader_transformation_external_yuv_es3.glsl
assets/shaders/fragment_shader_transformation_hdr_internal_es3.glsl
assets/shaders/fragment_shader_transformation_sdr_external_es2.glsl
assets/shaders/fragment_shader_transformation_sdr_internal_es2.glsl
assets/shaders/fragment_shader_transformation_sdr_oetf_es2.glsl
assets/shaders/fragment_shader_transformation_ultra_hdr_es3.glsl
assets/shaders/insert_overlay_fragment_shader_methods.glsl
assets/shaders/insert_ultra_hdr.glsl
assets/shaders/vertex_shader_thumbnail_strip_es2.glsl
assets/shaders/vertex_shader_transformation_es2.glsl
assets/shaders/vertex_shader_transformation_es3.glsl
assets/tag_edit.json
assets/tag_icon.json
```

## Selected compiled resource names

The following names confirm the UI concepts visible in the captures:

```text
color/bottom_bar_menu_icon_color
color/bottom_bar_menu_text_color
color/bottom_menu_list_bg_color
color/bottom_menu_list_circle_bg_color
color/bottom_navigation_view_bg_color
color/bottom_search_bar_background_color
color/bottom_tab_menu_icon_color
color/collection_footer_button_color
color/gallery_color_primary
color/gallery_color_primary_dark
color/gallery_navigation_bar_background_color
color/gallery_search_header_view_title_color
color/gallery_search_view_bg_color
color/gallery_status_bar_background_color
array/albums_column_count
array/n_album_pictures_column_count
array/search_*_column_count
array/visual_search_*_column_count
```

Day/night equivalents were also present for many of these resources.

## Safe implementation policy

- Use these names only to understand feature boundaries and states.
- Recreate the visual language using public Samsung guidance, Jetpack Compose, Material components, system colors, and original vectors.
- Do not extract or redistribute Samsung Gallery icons, fonts, Lottie files, shaders, layouts, or other binaries.
- Do not depend on Samsung-private framework APIs; the app must remain functional on non-Samsung Android devices.
