1. Add `redCylinderRenderable` to `MainActivity`
2. Update `setupRenderable()` to initialize `redCylinderRenderable` from the red material
3. Update `drawFinalLine()` to use `redCylinderRenderable` with local scaling, replacing `MaterialFactory.makeOpaqueWithColor`
4. Document the rationale for why this change improves performance in PR since it's impractical to measure Sceneform native `MaterialFactory` performance in unit tests.
5. Execute pre-commit instructions
6. Submit
