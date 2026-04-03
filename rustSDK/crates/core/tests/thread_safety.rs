//! Thread-safety tests: concurrent generate and decode must not panic.
//!
//! PRD 5.2 requires thread safety for all public API functions.

use std::thread;

use rusty_qr_core::{decoder, encoder};

#[test]
fn concurrent_generate_does_not_panic() {
    let handles: Vec<_> = (0..8)
        .map(|i| {
            thread::spawn(move || {
                let content = format!("thread-{i}");
                encoder::generate_png(&content, 256)
            })
        })
        .collect();

    for handle in handles {
        let result = handle.join().expect("thread must not panic");
        assert!(
            result.is_ok(),
            "generate_png must succeed: {:?}",
            result.err()
        );
    }
}

#[test]
fn concurrent_decode_does_not_panic() {
    let png = encoder::generate_png("concurrent decode", 256).unwrap();

    let handles: Vec<_> = (0..8)
        .map(|_| {
            let data = png.clone();
            thread::spawn(move || decoder::decode(&data))
        })
        .collect();

    for handle in handles {
        let result = handle.join().expect("thread must not panic");
        let scan = result.unwrap();
        assert_eq!(scan.content, "concurrent decode");
    }
}

#[test]
fn concurrent_mixed_operations() {
    let png = encoder::generate_png("mixed ops", 256).unwrap();

    let handles: Vec<_> = (0..8)
        .map(|i| {
            let data = png.clone();
            thread::spawn(move || {
                if i % 2 == 0 {
                    let content = format!("gen-{i}");
                    encoder::generate_png(&content, 256).map(|_| ())
                } else {
                    decoder::decode(&data).map(|_| ())
                }
            })
        })
        .collect();

    for handle in handles {
        let result = handle.join().expect("thread must not panic");
        assert!(
            result.is_ok(),
            "mixed operation must succeed: {:?}",
            result.err()
        );
    }
}
