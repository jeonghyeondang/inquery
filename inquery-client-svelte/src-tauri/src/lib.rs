use tauri_plugin_shell::ShellExt;
use tauri_plugin_shell::process::CommandEvent;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .setup(|app| {
            if cfg!(debug_assertions) {
                app.handle().plugin(
                    tauri_plugin_log::Builder::default()
                        .level(log::LevelFilter::Info)
                        .build(),
                )?;
            }

            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                start_backend(&handle).await;
            });

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

async fn start_backend(app: &tauri::AppHandle) {
    let sidecar_command = app
        .shell()
        .sidecar("inquery-server")
        .expect("failed to create sidecar command");

    let (mut rx, _child) = sidecar_command
        .spawn()
        .expect("failed to spawn sidecar");

    while let Some(event) = rx.recv().await {
        match event {
            CommandEvent::Stdout(line) => {
                let line = String::from_utf8_lossy(&line);
                log::info!("[backend] {}", line);
            }
            CommandEvent::Stderr(line) => {
                let line = String::from_utf8_lossy(&line);
                log::warn!("[backend] {}", line);
            }
            CommandEvent::Terminated(payload) => {
                log::error!("[backend] process terminated with code: {:?}", payload.code);
                break;
            }
            _ => {}
        }
    }
}
