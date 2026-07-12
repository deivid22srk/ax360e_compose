# ax360e_compose

Fork do projeto [ax360e](https://github.com/deivid22srk/ax360e) (emulador de Xbox 360 para Android, baseado no xenia-canary) com a interface completamente portada para **Jetpack Compose** usando **Material 3 Expressive**.

## Mudanças em relação ao `ax360e` original

- **UI 100% Jetpack Compose** — todos os layouts XML (`activity_*.xml`, `game_item.xml`, `pad_edit_menu.xml`, `edit_seek_bar.xml`) e menus XML foram removidos.
- **Material 3 Expressive** — esquema de cores dinâmico (Android 12+), tipografia Expressive, formas com cantos arredondados maiores, paleta teal/verde-azulado consistente.
- **Settings reescrito do zero** — o framework `androidx.preference` (XML) foi substituído por um `SettingsTree` em Kotlin gerado a partir do `emulator_settings.xml` original; agora é renderizado inteiramente por componentes Compose (`Switch`, `Slider`, `AlertDialog`, etc.).
- **KeyMap, About, VirtualControlEdit, EmulatorActivity** — todas reescritas em Compose. O `VirtualControl` continua como `SurfaceView` Java (desenho customizado) e é hospedado via `AndroidView`.
- **Native C++ inalterado** — toda a integração com o xenia-canary permanece idêntica.
- **Capas de jogos (ISO/ZAR) via TheGamesDB** — jogos ISO e ZAR não contêm metadados de capa (ao contrário dos GOD/STFS). A UI agora busca automaticamente a capa em https://api.thegamesdb.net (plataforma Xbox 360, id 15) usando o nome do arquivo como query. Resultados são cacheados em disco (`filesDir/covers/`) com sentinela `.miss` para evitar re-fetch de jogos sem capa. O usuário precisa cadastrar uma API key gratuita em https://thegamesdb.net/key.php e colá-la em **⋮ → Cover Art Settings**. Sem a chave, o ícone placeholder é exibido.

## Estrutura

```
app/src/main/java/aenu/ax360e/
├── MainActivity.kt              # Hosts MainScreen (Compose)
├── AboutActivity.kt             # Hosts AboutScreen (Compose)
├── KeyMapActivity.kt            # Hosts KeyMapScreen (Compose)
├── EmulatorSettings.kt          # Hosts EmulatorSettingsScreen (Compose)
├── VirtualControlEdit.kt        # Compose + AndroidView(VirtualControl)
├── EmulatorActivity.kt          # Compose + AndroidView(SurfaceView + VirtualControl)
├── Emulator.java                # Bridge para native (preservado)
├── Application.java             # Preservado
├── DocumentsProvider.java       # ContentProvider (preservado)
├── VirtualControl.java          # SurfaceView custom (preservado)
├── Utils.java                   # Preservado
├── ProgressTask.java            # Preservado
├── KeyMapConfig.java            # Preservado
├── AppOpenAdManager.java        # Preservado
└── ui/
    ├── theme/                   # Theme.kt, Type.kt, Shape.kt (Material 3 Expressive)
    ├── components/              # GameCard.kt, EmptyState.kt
    ├── model/                   # GameItem.kt, GameListLoader.kt,
    │                            # CoverArtRepository.kt (TheGamesDB API),
    │                            # CoverArtCache.kt (disk + sentinel),
    │                            # CoverArtLoader.kt (memory → disk → network)
    └── screens/                 # MainScreen.kt, AboutScreen.kt, KeyMapScreen.kt,
                                 # EmulatorSettingsScreen.kt, SettingsTree.kt,
                                 # CoverArtSettingsDialog.kt
```

## Build

O build do APK é feito automaticamente pelo GitHub Actions em push para `main`. Veja `.github/workflows/build.yml`.

Para build local (Android Studio ou linha de comando):

```bash
./gradlew assembleDebug
```

Requer:
- JDK 17
- Android SDK 35
- NDK 27.2.12479018
- Vulkan SDK tools (`glslangValidator`, `spirv-opt`, `spirv-dis`) no PATH

## Licença

WTFPL — veja os cabeçalhos dos arquivos individuais.
