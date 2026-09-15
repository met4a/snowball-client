; Snowball Client installer look: dark title bar, pages and button bar to match the launcher.
; This file is included before electron-builder's template, so functions are added through macros
; that the template inserts after MUI2 and LogicLib are loaded.
!define MUI_BGCOLOR "05070A"
!define MUI_TEXTCOLOR "F2F6FA"

!include "${__FILEDIR__}\dark.nsh"

!ifdef BUILD_UNINSTALLER
  !define MUI_CUSTOMFUNCTION_UNGUIINIT un.SnowballDarkOuter
!else
  !define MUI_CUSTOMFUNCTION_GUIINIT SnowballDarkOuter
!endif

!macro customHeader
  !ifdef BUILD_UNINSTALLER
    !insertmacro SNOWBALL_DARK_FUNCTIONS "un."
  !else
    !insertmacro SNOWBALL_DARK_FUNCTIONS ""

    ; The folder page is added in customPageAfterChangeDir (so it can be styled). Defining this keeps
    ; the template's shortcut handling for installs into a changed folder.
    !define allowToChangeInstallationDirectory
    !include StrContains.nsh

    ; Same as the template: always install into a "Snowball Client Launcher" sub-folder.
    Function SnowballInstFilesPre
      ${StrContains} $0 "${APP_FILENAME}" $INSTDIR
      ${If} $0 == ""
        StrCpy $INSTDIR "$INSTDIR\${APP_FILENAME}"
      ${EndIf}
    FunctionEnd
  !endif
!macroend

!macro customWelcomePage
  !define MUI_WELCOMEPAGE_TITLE "Welcome to Snowball Client"
  !define MUI_WELCOMEPAGE_TEXT "Setup will install Snowball Client, a free launcher and client for Minecraft: Java Edition.$\r$\n$\r$\n-  Separate instances for every version and modpack$\r$\n-  Browse and install mods from Modrinth in one click$\r$\n-  FPS Boost, PvP HUD and a radial in-game menu$\r$\n$\r$\nNo cheats, no ads. Click Next to continue."
  !define MUI_PAGE_CUSTOMFUNCTION_SHOW SnowballHideLines
  !insertmacro MUI_PAGE_WELCOME
  ; Applies to the next page: "Choose Installation Options".
  !define MUI_PAGE_CUSTOMFUNCTION_SHOW SnowballDarkPage
!macroend

!macro customPageAfterChangeDir
  !insertmacro skipPageIfUpdated
  !define MUI_PAGE_CUSTOMFUNCTION_SHOW SnowballDarkPage
  !insertmacro MUI_PAGE_DIRECTORY
  ; Applies to the template's install progress page, which follows.
  !define MUI_PAGE_CUSTOMFUNCTION_PRE SnowballInstFilesPre
  !define MUI_PAGE_CUSTOMFUNCTION_SHOW SnowballDarkPage
!macroend

!macro customFinishPage
  Function StartApp
    ${if} ${isUpdated}
      StrCpy $1 "--updated"
    ${else}
      StrCpy $1 ""
    ${endif}
    ${StdUtils.ExecShellAsUser} $0 "$launchLink" "open" "$1"
  FunctionEnd

  !define MUI_FINISHPAGE_TITLE "Snowball Client is ready"
  !define MUI_FINISHPAGE_TEXT "Sign in with your Microsoft account, create an instance and press Play. The launcher downloads Minecraft, Java and your mods for you.$\r$\n$\r$\nPress Right Shift in game to open the Snowball menu."
  !define MUI_FINISHPAGE_RUN
  !define MUI_FINISHPAGE_RUN_TEXT "Open Snowball Client now"
  !define MUI_FINISHPAGE_RUN_FUNCTION "StartApp"
  !define MUI_PAGE_CUSTOMFUNCTION_SHOW SnowballFinishShow
  !insertmacro MUI_PAGE_FINISH

  ; Defined after MUI_PAGE_FINISH, which declares $mui.FinishPage.Run.
  ; Themed checkboxes ignore custom text colours, which would leave black text on the dark page.
  Function SnowballFinishShow
    StrCpy $0 $mui.FinishPage.Run
    System::Call 'uxtheme::SetWindowTheme(p r0, w " ", w " ")'
    SetCtlColors $mui.FinishPage.Run "F2F6FA" "05070A"
    Call SnowballHideLines
  FunctionEnd
!macroend

!macro customUnWelcomePage
  !define MUI_WELCOMEPAGE_TITLE "Uninstall Snowball Client"
  !define MUI_WELCOMEPAGE_TEXT "This removes the Snowball Client launcher from this PC.$\r$\n$\r$\nYour instances, worlds, screenshots and settings in %APPDATA%\SnowballClientLauncher are kept, so everything is still there if you install it again.$\r$\n$\r$\nClick Next to continue."
  !define MUI_FINISHPAGE_TITLE "Snowball Client was removed"
  !define MUI_FINISHPAGE_TEXT "Thanks for playing. Your game data was kept."
  !define MUI_PAGE_CUSTOMFUNCTION_SHOW un.SnowballHideLines
  !insertmacro MUI_UNPAGE_WELCOME
  !define MUI_PAGE_CUSTOMFUNCTION_SHOW un.SnowballDarkPage
!macroend

; The uninstall progress page has no show hook in the template, so it is styled when removal starts.
!macro customUnInstall
  ${IfNot} ${Silent}
    Call un.SnowballDarkPage
  ${EndIf}
!macroend
