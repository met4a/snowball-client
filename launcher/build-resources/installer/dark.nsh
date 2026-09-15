; Dark window chrome for the Snowball Client installer and uninstaller (Windows 10 1809+ and 11).
; Only macros live here: SNOWBALL_DARK_FUNCTIONS is inserted after MUI2 and LogicLib are loaded.
; Older Windows versions ignore the dark-mode calls and keep the normal look.

!macro SNOWBALL_DARK_BUTTON HWND
  System::Call 'uxtheme::#133(p ${HWND}, i 1)'
  System::Call 'uxtheme::SetWindowTheme(p ${HWND}, w "DarkMode_Explorer", p 0)'
!macroend

!macro SNOWBALL_DARK_FUNCTIONS UN
  ; Title bar, button bar and the lines around it. Runs once when the window is created.
  Function ${UN}SnowballDarkOuter
    Push $0
    System::Call 'uxtheme::#135(i 2)'
    System::Call 'uxtheme::#104()'
    System::Call 'dwmapi::DwmSetWindowAttribute(p $HWNDPARENT, i 20, *i 1, i 4)'
    System::Call 'dwmapi::DwmSetWindowAttribute(p $HWNDPARENT, i 35, *i 0x000A0705, i 4)'
    SetCtlColors $HWNDPARENT "F2F6FA" "05070A"
    GetDlgItem $0 $HWNDPARENT 1
    !insertmacro SNOWBALL_DARK_BUTTON $0
    GetDlgItem $0 $HWNDPARENT 2
    !insertmacro SNOWBALL_DARK_BUTTON $0
    GetDlgItem $0 $HWNDPARENT 3
    !insertmacro SNOWBALL_DARK_BUTTON $0
    GetDlgItem $0 $HWNDPARENT 1028
    SetCtlColors $0 "4F5A66" "05070A"
    Pop $0
    Call ${UN}SnowballHideLines
  FunctionEnd

  ; Hides the light etched separator lines (SS_ETCHEDHORZ statics) that MUI shows again on each page.
  Function ${UN}SnowballHideLines
    ${If} $HWNDPARENT == 0
      Return
    ${EndIf}
    Push $0
    Push $1
    StrCpy $0 0
    ${Do}
      FindWindow $0 "Static" "" $HWNDPARENT $0
      ${If} $0 == 0
        ${ExitDo}
      ${EndIf}
      System::Call 'user32::GetWindowLongW(p $0, i -16) i .r1'
      IntOp $1 $1 & 0x1F
      ${If} $1 = 16
        ShowWindow $0 0
      ${EndIf}
    ${Loop}
    Pop $1
    Pop $0
  FunctionEnd

  ; Colours every control on the current inner page (labels, radio buttons, edit boxes, progress bar, log).
  Function ${UN}SnowballDarkPage
    ${If} $HWNDPARENT == 0
      Return
    ${EndIf}
    Push $R0
    Push $R1
    Push $R2
    Push $R3
    FindWindow $R0 "#32770" "" $HWNDPARENT
    ${If} $R0 != 0
      SetCtlColors $R0 "F2F6FA" "05070A"
      StrCpy $R1 0
      ${Do}
        FindWindow $R1 "" "" $R0 $R1
        ${If} $R1 == 0
          ${ExitDo}
        ${EndIf}
        System::Call 'user32::GetClassNameW(p $R1, w .R2, i 64)'
        ${If} $R2 == "Button"
          System::Call 'user32::GetWindowLongW(p $R1, i -16) i .R3'
          IntOp $R3 $R3 & 0xF
          ${If} $R3 <= 1
            !insertmacro SNOWBALL_DARK_BUTTON $R1
          ${Else}
            ; Themed radio buttons, checkboxes and group boxes ignore text colours.
            System::Call 'uxtheme::SetWindowTheme(p $R1, w " ", w " ")'
            SetCtlColors $R1 "F2F6FA" "05070A"
          ${EndIf}
        ${ElseIf} $R2 == "Edit"
          SetCtlColors $R1 "F2F6FA" "10151C"
        ${ElseIf} $R2 == "msctls_progress32"
          System::Call 'uxtheme::SetWindowTheme(p $R1, w " ", w " ")'
          SendMessage $R1 0x0409 0 0x00FFCB7F
          SendMessage $R1 0x2001 0 0x001C1510
        ${ElseIf} $R2 == "SysListView32"
          SendMessage $R1 0x1001 0 0x000A0705
          SendMessage $R1 0x1026 0 0x000A0705
          SendMessage $R1 0x1024 0 0x00FAF6F2
          !insertmacro SNOWBALL_DARK_BUTTON $R1
        ${Else}
          SetCtlColors $R1 "F2F6FA" "05070A"
        ${EndIf}
      ${Loop}
    ${EndIf}
    Pop $R3
    Pop $R2
    Pop $R1
    Pop $R0
    Call ${UN}SnowballHideLines
  FunctionEnd
!macroend
