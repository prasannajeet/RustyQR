.PHONY: test clean all
.DEFAULT_GOAL := all

test:
	cargo test --workspace
	cargo clippy --workspace --all-targets -- -D warnings
	cargo fmt --check

clean:
	cargo clean
	@echo "Cleaned Rust build artifacts"
	@echo "Note: generated bindings are cleaned by the Gradle 'clean' tasks; the Xcode project by ./gradlew cleanXcodeProject"

all: test
