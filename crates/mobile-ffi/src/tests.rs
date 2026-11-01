use super::*;

/// Wraps `objs` (bodies only, each becomes `N 0 obj ... endobj` for its 1-based position) into a
/// minimal, syntactically valid PDF: header, objects, an xref table and a trailer pointing at
/// object 1 as `/Root`.
fn minimal_pdf(objs: &[Vec<u8>]) -> Vec<u8> {
    let mut out = b"%PDF-1.7\n".to_vec();
    let mut offsets = Vec::new();
    for (i, o) in objs.iter().enumerate() {
        offsets.push(out.len());
        out.extend_from_slice(format!("{} 0 obj\n", i + 1).as_bytes());
        out.extend_from_slice(o);
        out.extend_from_slice(b"\nendobj\n");
    }
    let xref = out.len();
    out.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", objs.len() + 1).as_bytes());
    for o in offsets {
        out.extend_from_slice(format!("{o:010} 00000 n \n").as_bytes());
    }
    out.extend_from_slice(format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n", objs.len() + 1).as_bytes());
    out
}

/// A minimal `n`-page document; page `i` shows "Page i+1" (mirrors
/// `pdfcraft_engine::tests::fixture`, which is private to that crate).
fn fixture(n: usize) -> Vec<u8> {
    let mut objs: Vec<Vec<u8>> = vec![b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()];
    let kids: Vec<String> = (0..n).map(|i| format!("{} 0 R", 4 + 2 * i)).collect();
    objs.push(format!("<< /Type /Pages /Kids [{}] /Count {n} /MediaBox [0 0 200 300] >>", kids.join(" ")).into_bytes());
    objs.push(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_vec());
    for i in 0..n {
        objs.push(format!("<< /Type /Page /Parent 2 0 R /Contents {} 0 R /Resources << /Font << /F1 3 0 R >> >> >>", 5 + 2 * i).into_bytes());
        let body = format!("BT /F1 24 Tf 20 150 Td (Page {}) Tj ET", i + 1);
        objs.push(format!("<< /Length {} >>\nstream\n{body}\nendstream", body.len()).into_bytes());
    }
    minimal_pdf(&objs)
}

/// One page, one object (4 0 obj), whose content draws "Page" and "one" on two separate
/// baselines — mirrors `fixture`'s build-a-minimal-PDF-by-hand approach, but with a
/// hand-written content stream instead of `fixture`'s single-`Td` one-liner.
fn two_line_fixture() -> Vec<u8> {
    let body = "BT /F1 24 Tf 20 200 Td (Page) Tj 0 -30 Td (one) Tj ET";
    minimal_pdf(&[
        b"<< /Type /Catalog /Pages 2 0 R >>".to_vec(),
        b"<< /Type /Pages /Kids [4 0 R] /Count 1 /MediaBox [0 0 200 300] >>".to_vec(),
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_vec(),
        b"<< /Type /Page /Parent 2 0 R /Contents 5 0 R /Resources << /Font << /F1 3 0 R >> >> >>".to_vec(),
        format!("<< /Length {} >>\nstream\n{body}\nendstream", body.len()).into_bytes(),
    ])
}

/// One page with no content stream at all — nothing to find or select.
fn blank_page_fixture() -> Vec<u8> {
    minimal_pdf(&[
        b"<< /Type /Catalog /Pages 2 0 R >>".to_vec(),
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 300] >>".to_vec(),
        b"<< /Type /Page /Parent 2 0 R /Resources << >> >>".to_vec(),
    ])
}

#[test]
fn opens_a_document_and_reports_its_page_count() {
    let doc = MobileDocument::open(fixture(3), None).expect("opens");
    assert_eq!(doc.page_count(), 3);
    assert_eq!(doc.title(), None);
    assert!(doc.outline().is_empty());
}

#[test]
fn renders_a_page_to_a_non_empty_rgba_buffer_of_the_right_size() {
    let doc = MobileDocument::open(fixture(1), None).expect("opens");
    let page = doc.render_page(0, 1.0).expect("renders");
    assert!(page.width > 0 && page.height > 0);
    assert_eq!(page.rgba.len(), page.width as usize * page.height as usize * 4);
}

#[test]
fn rendering_a_page_past_the_end_is_an_error_not_a_panic() {
    let doc = MobileDocument::open(fixture(2), None).expect("opens");
    assert!(matches!(doc.render_page(2, 1.0), Err(MobileError::NoSuchPage { index: 2 })));
}

#[test]
fn opening_bytes_that_are_not_a_pdf_is_an_error_not_a_panic() {
    assert!(matches!(MobileDocument::open(b"not a pdf".to_vec(), None), Err(MobileError::Invalid)));
}

#[test]
fn search_finds_a_phrase_on_its_own_page_only() {
    let doc = MobileDocument::open(fixture(3), None).expect("opens");
    let matches = doc.search("Page 2".to_owned(), false, false, 1.0);
    assert_eq!(matches.len(), 1);
    assert_eq!(matches[0].page, 1);
    assert!(matches[0].snippet.contains("Page 2"));
    assert_eq!(matches[0].rects.len(), 1);
    assert!(matches[0].rects[0].x1 > matches[0].rects[0].x0);
    assert!(matches[0].rects[0].y1 > matches[0].rects[0].y0);
}

#[test]
fn search_is_case_insensitive_unless_asked_not_to_be() {
    let doc = MobileDocument::open(fixture(1), None).expect("opens");
    assert_eq!(doc.search("page 1".to_owned(), false, false, 1.0).len(), 1);
    assert_eq!(doc.search("page 1".to_owned(), true, false, 1.0).len(), 0);
}

#[test]
fn search_for_text_on_no_page_returns_no_matches() {
    let doc = MobileDocument::open(fixture(3), None).expect("opens");
    assert!(doc.search("nonexistent".to_owned(), false, false, 1.0).is_empty());
}

#[test]
fn a_match_spanning_a_line_wrap_is_one_match_with_a_rect_per_line() {
    let doc = MobileDocument::open(two_line_fixture(), None).expect("opens");
    let matches = doc.search("Page one".to_owned(), false, false, 1.0);
    assert_eq!(matches.len(), 1, "expected one match spanning both lines, got {matches:?}");
    assert_eq!(matches[0].rects.len(), 2, "expected one rect per line, got {:?}", matches[0].rects);
}

/// `render_page`'s pixels scale with `scale`; `TextGlyph::rect` (and so a match's `rects`) does
/// not unless `search` itself applies it — `RequestKind::Text` never reads `RenderRequest::scale`
/// (`crates/render/src/raster.rs`). Regression for exactly that: at `scale` 1.0 the earlier,
/// unscaled tests above happened to pass regardless, which is why this needs its own case at a
/// scale that actually exposes it.
#[test]
fn search_rects_line_up_with_a_page_rendered_at_the_same_scale() {
    let doc = MobileDocument::open(fixture(1), None).expect("opens");
    let page = doc.render_page(0, 2.0).expect("renders");
    let matches = doc.search("Page 1".to_owned(), false, false, 2.0);
    assert_eq!(matches.len(), 1);
    let rect = matches[0].rects[0];
    assert!(rect.x1 <= page.width as f32 + 0.01, "{rect:?} doesn't fit page width {}", page.width);
    assert!(rect.y1 <= page.height as f32 + 0.01, "{rect:?} doesn't fit page height {}", page.height);
    // Not just "within bounds" by accident: before the fix the point-space rect, read as device
    // pixels at 2x scale, was squeezed into a sliver near the origin instead of a real highlight.
    assert!(rect.x1 - rect.x0 > 10.0, "{rect:?} is too small to be a real highlight");

    let at_1x = doc.search("Page 1".to_owned(), false, false, 1.0);
    let unscaled = at_1x[0].rects[0];
    assert!((rect.x0 - unscaled.x0 * 2.0).abs() < 0.01, "{unscaled:?} doubled should be {rect:?}");
    assert!((rect.y1 - unscaled.y1 * 2.0).abs() < 0.01, "{unscaled:?} doubled should be {rect:?}");
}

/// A point near the left edge of "Page 1"'s bounding rect (from `search`, which uses the same
/// point-space `word_at` does) should land on "Page", not "1".
fn page_word_point(doc: &MobileDocument) -> (f32, f32) {
    let matches = doc.search("Page 1".to_owned(), false, false, 1.0);
    let phrase = matches[0].rects[0];
    (phrase.x0 + (phrase.x1 - phrase.x0) * 0.1, (phrase.y0 + phrase.y1) / 2.0)
}

#[test]
fn word_at_a_point_selects_the_word_there() {
    let doc = MobileDocument::open(fixture(1), None).expect("opens");
    let (x, y) = page_word_point(&doc);
    let selection = doc.word_at(0, x, y, 1.0).expect("finds a word");
    assert_eq!(selection.text, "Page");
    assert!(!selection.rects.is_empty());
}

#[test]
fn word_at_on_a_blank_page_finds_nothing() {
    let doc = MobileDocument::open(blank_page_fixture(), None).expect("opens");
    assert!(doc.word_at(0, 50.0, 50.0, 1.0).is_none());
}

#[test]
fn extend_selection_grows_to_the_new_point_regardless_of_drag_direction() {
    let doc = MobileDocument::open(fixture(1), None).expect("opens");
    let matches = doc.search("Page 1".to_owned(), false, false, 1.0);
    let phrase = matches[0].rects[0];
    let (left_x, right_x) = (phrase.x0 + (phrase.x1 - phrase.x0) * 0.1, phrase.x0 + (phrase.x1 - phrase.x0) * 0.9);
    let y = (phrase.y0 + phrase.y1) / 2.0;

    let word = doc.word_at(0, left_x, y, 1.0).expect("finds a word");
    assert_eq!(word.text, "Page");
    // Drag the other handle out to "1": extends forward.
    let extended = doc.extend_selection(0, word.start_glyph, right_x, y, 1.0).expect("extends");
    assert_eq!(extended.text, "Page 1");

    // Starting from "1" and dragging the other handle back past the anchor to "Page" should give
    // the same range, not an inverted or empty one.
    let other_word = doc.word_at(0, right_x, y, 1.0).expect("finds a word");
    let extended_back = doc.extend_selection(0, other_word.start_glyph, left_x, y, 1.0).expect("extends");
    assert_eq!(extended_back.text, "Page 1");
}

/// Mirrors `search_rects_line_up_with_a_page_rendered_at_the_same_scale`: the same bug class
/// (`PageText`'s rects are unscaled points) applies to selection rects, so it gets the same test.
#[test]
fn selection_rects_scale_with_the_requested_scale() {
    let doc = MobileDocument::open(fixture(1), None).expect("opens");
    let (x, y) = page_word_point(&doc);
    let at_1x = doc.word_at(0, x, y, 1.0).expect("finds a word");
    let at_2x = doc.word_at(0, x * 2.0, y * 2.0, 2.0).expect("finds a word");
    assert_eq!(at_1x.text, at_2x.text);
    let (r1, r2) = (at_1x.rects[0], at_2x.rects[0]);
    assert!((r2.x0 - r1.x0 * 2.0).abs() < 0.01, "{r1:?} vs {r2:?}");
    assert!((r2.y1 - r1.y1 * 2.0).abs() < 0.01, "{r1:?} vs {r2:?}");
}
