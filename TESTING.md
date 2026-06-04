# Testing

This project uses a small, modern JVM testing stack. Tests are fast, deterministic, and mirror the
production package layout.

## Stack

| Tool | Version | Purpose |
| --- | --- | --- |
| JUnit | 6.1.0 (BOM) | Test framework / runner (JUnit Jupiter + Platform) |
| AssertJ | 3.27.3 | Fluent, readable assertions |
| MockBukkit | `mockbukkit-v26.1.2:4.113.1` | In-memory Paper server for tests that need real `BlockData`/`Material`/`Tag` |
| Mockito | 5.23.0 | Mocking collaborators where a real object is impractical |

Versions are pinned in [`build.gradle`](build.gradle) to the latest stable releases that support
**Java 25** and **Paper 26.1**. The MockBukkit module is version-matched to `paper-api:26.1.2`, so
mocked block data behaves exactly like production. MockBukkit downloads the real Paper server on the
first test run (cached afterwards), so the initial run needs network access.

## Running

```bash
./gradlew test                # run everything
./gradlew test --tests '*CarpetPayloads*'              # one class
./gradlew test --tests '*BlockPlacementProtocol*orientation*'   # one nested group
```

The HTML report is written to `build/reports/tests/test/index.html`.

## Structure mirrors production

Each production class has a sibling test class in the same package under `src/test/java`:

```
src/main/java/net/dungeondev/accurateblockplacement/
    CarpetPayloads.java
    BlockPlacementProtocol.java
    AccurateBlockPlacement.java   (transport adapter - see "What we test" below)

src/test/java/net/dungeondev/accurateblockplacement/
    CarpetPayloadsTest.java
    BlockPlacementProtocolTest.java
```

Because tests share the production package, they can exercise package-private domain classes
directly without widening visibility for testing's sake.

## Conventions

- **Naming.** Test class is `<ProductionClass>Test`. Test methods describe behaviour, not mechanics
  (`reversesFacing`, not `testApply2`). Use `@DisplayName` for human-readable report output.
- **Grouping.** Use `@Nested` classes to group related cases (one per method or behaviour area). This
  keeps MockBukkit setup scoped to only the tests that need it.
- **Assertions.** Use AssertJ (`assertThat(...)`) exclusively, with `.as(...)` descriptions on
  non-obvious checks. Do not mix in JUnit `Assertions.*`.
- **Parameterised tests.** Prefer `@ParameterizedTest` with `@ValueSource`/`@CsvSource` for
  table-style logic (facing maps, encode/decode round-trips) instead of copy-pasted cases.
- **Pure vs. server-backed.** Keep pure logic (arithmetic, enum mapping, serialization) free of any
  Bukkit server so those tests stay instant. Only reach for MockBukkit when a test genuinely needs a
  real `BlockData`, `Material`, or `Tag`.
- **MockBukkit lifecycle.** Always pair `MockBukkit.mock()` in `@BeforeEach` with
  `MockBukkit.unmock()` in `@AfterEach` so each test gets a clean server and nothing leaks between
  tests.
- **Independent verification.** When asserting a serialized byte format, decode it with an
  independent reader (e.g. `DataInputStream`) rather than the production writer, so the test proves
  the wire format instead of merely round-tripping a bug.

## What we test (and what we don't)

The codebase is split so that the testable logic is isolated from the hard-to-test transport:

- **`CarpetPayloads`** — pure wire-format serialization. Fully unit-tested, including byte-parity of
  the `carpet:hello` handshake (the contract clients depend on).
- **`BlockPlacementProtocol`** — protocol decoding and block-orientation rules. Pure parts are unit
  tested; orientation is tested against real MockBukkit `BlockData`.
- **`AccurateBlockPlacement`** — the thin PacketEvents/Bukkit adapter. It is intentionally kept free
  of decoding logic. Its packet plumbing depends on a live Netty pipeline that MockBukkit does not
  provide, so it is verified by **manual/integration testing** with a real client (Litematica
  easyPlace / Tweakeroo Flexible Block Placement) rather than unit tests. See `README.md`.

When adding a feature, put the logic in a domain class so it can be unit-tested, and keep the plugin
class a thin wire-up.
