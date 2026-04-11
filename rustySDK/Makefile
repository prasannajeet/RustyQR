.PHONY: test android ios clean all
.DEFAULT_GOAL := all

test:
	cargo test --workspace
	cargo clippy --workspace -- -D warnings
	cargo fmt --check

android:
	./scripts/build_android.sh

ios:
	@echo "iOS build not yet implemented (Phase 6)"

clean:
	cargo clean
	rm -rf ../composeApp/src/androidMain/jniLibs
	rm -rf ../composeApp/src/androidMain/kotlin/generated
	@echo "Cleaned Rust build artifacts and generated bindings"

all: test android
