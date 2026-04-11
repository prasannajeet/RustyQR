.PHONY: test android ios clean all
.DEFAULT_GOAL := all

test:
	cargo test --workspace
	cargo clippy --workspace -- -D warnings
	cargo fmt --check

android:
	./scripts/build_android.sh

ios:
	./scripts/build_ios.sh

clean:
	cargo clean
	rm -rf ../composeApp/src/androidMain/jniLibs
	rm -rf ../composeApp/src/androidMain/kotlin/generated
	rm -rf ../iosApp/Frameworks/
	rm -rf ../iosApp/generated/
	rm -rf ../iosApp/iosApp.xcodeproj/
	@echo "Cleaned Rust build artifacts and generated bindings"

all: test android ios
