<script lang="ts">
	import { goto } from '$app/navigation';
	import i18n from '$lib/i18n';
	import { login } from '$lib/service/user';
	import { queryCurUser } from '$lib/stores/user.svelte';
	import { User, Lock } from 'lucide-svelte';

	const isTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

	let userName = $state('');
	let password = $state('');
	let loading = $state(false);
	let error = $state('');

	async function handleLogin() {
		if (!userName) {
			error = i18n('login.form.user.placeholder');
			return;
		}
		if (!password) {
			error = i18n('login.form.password.placeholder');
			return;
		}

		loading = true;
		error = '';

		try {
			const token = await login({ userName, password });
			if (token && typeof token === 'string' && typeof window !== 'undefined') {
				localStorage.setItem('Inquery', token);
			}
			const user = await queryCurUser();
			if (token || user) {
				goto('/connections');
			}
		} catch (e: unknown) {
			error = (e as Error)?.message || 'Login failed';
		} finally {
			loading = false;
		}
	}

	function handleKeyDown(e: KeyboardEvent) {
		if (e.key === 'Enter') handleLogin();
	}
</script>

<div class="login-page">
	{#if isTauri}
		<div data-tauri-drag-region class="fixed top-0 left-0 right-0 h-[38px] z-50"></div>
	{/if}
	<!-- Floating particles -->
	<div class="particles">
		{#each Array(9) as _, i}
			<div class="particle"></div>
		{/each}
	</div>

	<div class="container">
		<div class="card">
			<!-- Logo section -->
			<div class="logo-section">
				<div class="logo-text">Inquery</div>
				<div class="tagline">AI-Powered Database Assistant</div>
			</div>

			<!-- Welcome text -->
			<div class="welcome">{i18n('login.text.welcome')}</div>
			<div class="subtitle">Sign in to continue to your workspace</div>

			<!-- Login form -->
			<form class="form" onsubmit={(e) => { e.preventDefault(); handleLogin(); }}>
				<!-- Username -->
				<div class="input-wrapper">
					<User size={18} class="input-icon" />
					<input
						type="text"
						bind:value={userName}
						placeholder={i18n('login.form.user')}
						onkeydown={handleKeyDown}
						autocomplete="off"
						class="login-input"
					/>
				</div>

				<!-- Password -->
				<div class="input-wrapper">
					<Lock size={18} class="input-icon" />
					<input
						type="password"
						bind:value={password}
						placeholder={i18n('login.form.password')}
						onkeydown={handleKeyDown}
						autocomplete="new-password"
						class="login-input"
					/>
				</div>

			<!-- Error -->
			{#if error}
				<p class="text-sm text-red-400 text-center">{error}</p>
			{/if}

			<!-- Submit -->
				<button type="submit" disabled={loading} class="submit-btn">
					{#if loading}
						<span class="inline-flex items-center gap-2">
							<span class="animate-spin rounded-full h-4 w-4 border-b-2 border-white"></span>
							Loading...
						</span>
					{:else}
						{i18n('login.button.login')}
					{/if}
				</button>
			</form>
		</div>
	</div>

</div>

<style>
	.login-page {
		min-height: 100vh;
		display: flex;
		align-items: center;
		justify-content: center;
		background: linear-gradient(135deg, #0f0f1a 0%, #1a1a2e 50%, #16213e 100%);
		position: relative;
		overflow: hidden;
	}

	.login-page::before {
		content: '';
		position: absolute;
		inset: 0;
		background-image: linear-gradient(rgba(99, 102, 241, 0.03) 1px, transparent 1px),
			linear-gradient(90deg, rgba(99, 102, 241, 0.03) 1px, transparent 1px);
		background-size: 50px 50px;
		animation: gridMove 20s linear infinite;
	}

	.login-page::after {
		content: '';
		position: absolute;
		width: 600px;
		height: 600px;
		background: radial-gradient(circle, rgba(99, 102, 241, 0.15) 0%, transparent 70%);
		top: -200px;
		right: -200px;
		pointer-events: none;
	}

	@keyframes gridMove {
		0% { transform: translate(0, 0); }
		100% { transform: translate(50px, 50px); }
	}

	.container {
		position: relative;
		z-index: 1;
		width: 100%;
		max-width: 420px;
		padding: 0 24px;
	}

	.card {
		background: hsl(var(--card) / 0.8);
		backdrop-filter: blur(20px);
		border: 1px solid hsl(var(--border) / 0.5);
		border-radius: 20px;
		padding: 40px;
		box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.4), 0 0 0 1px rgba(99, 102, 241, 0.1);
	}

	:global(.dark) .card {
		background: rgba(30, 30, 40, 0.8);
		border-color: rgba(99, 102, 241, 0.2);
	}

	.logo-section {
		display: flex;
		flex-direction: column;
		align-items: center;
		margin-bottom: 32px;
	}

	.logo-text {
		font-size: 36px;
		font-weight: 700;
		background: linear-gradient(135deg, #fff 0%, #a5b4fc 100%);
		-webkit-background-clip: text;
		-webkit-text-fill-color: transparent;
		background-clip: text;
		letter-spacing: -1px;
	}

	.tagline {
		font-size: 14px;
		color: hsl(var(--muted-foreground));
		margin-top: 8px;
		text-align: center;
	}

	.welcome {
		font-size: 20px;
		font-weight: 600;
		color: hsl(var(--foreground));
		text-align: center;
		margin-bottom: 8px;
	}

	.subtitle {
		font-size: 14px;
		color: hsl(var(--muted-foreground));
		text-align: center;
		margin-bottom: 28px;
	}

	.form {
		display: flex;
		flex-direction: column;
		gap: 16px;
	}

	.input-wrapper {
		position: relative;
	}

	.input-wrapper :global(.input-icon) {
		position: absolute;
		left: 14px;
		top: 50%;
		transform: translateY(-50%);
		color: hsl(var(--muted-foreground));
		z-index: 1;
		pointer-events: none;
	}

	.login-input {
		width: 100%;
		height: 48px;
		padding: 0 16px 0 44px;
		background: hsl(var(--muted) / 0.5);
		border: 1px solid hsl(var(--border));
		border-radius: 12px;
		font-size: 15px;
		color: hsl(var(--foreground));
		transition: all 0.2s ease;
		outline: none;
	}

	.login-input:hover {
		border-color: hsl(var(--primary) / 0.5);
	}

	.login-input:focus {
		border-color: hsl(var(--primary));
		box-shadow: 0 0 0 3px hsl(var(--primary) / 0.1);
	}

	.login-input::placeholder {
		color: hsl(var(--muted-foreground));
	}

	.submit-btn {
		width: 100%;
		height: 48px;
		border-radius: 12px;
		font-size: 15px;
		font-weight: 600;
		margin-top: 8px;
		background: linear-gradient(135deg, hsl(var(--primary)) 0%, hsl(var(--primary) / 0.8) 100%);
		border: none;
		color: hsl(var(--primary-foreground));
		box-shadow: 0 4px 14px hsl(var(--primary) / 0.4);
		transition: all 0.2s ease;
		cursor: pointer;
	}

	.submit-btn:hover {
		transform: translateY(-1px);
		box-shadow: 0 6px 20px hsl(var(--primary) / 0.5);
	}

	.submit-btn:active {
		transform: translateY(0);
	}

	.submit-btn:disabled {
		opacity: 0.6;
		cursor: not-allowed;
	}

	/* Particles */
	.particles {
		position: absolute;
		inset: 0;
		overflow: hidden;
		pointer-events: none;
	}

	.particle {
		position: absolute;
		width: 4px;
		height: 4px;
		background: hsl(var(--primary) / 0.4);
		border-radius: 50%;
		animation: float 15s infinite;
	}

	.particle:nth-child(1) { left: 10%; animation-delay: 0s; animation-duration: 12s; }
	.particle:nth-child(2) { left: 20%; animation-delay: 2s; animation-duration: 14s; }
	.particle:nth-child(3) { left: 30%; animation-delay: 4s; animation-duration: 16s; }
	.particle:nth-child(4) { left: 40%; animation-delay: 1s; animation-duration: 13s; }
	.particle:nth-child(5) { left: 50%; animation-delay: 3s; animation-duration: 15s; }
	.particle:nth-child(6) { left: 60%; animation-delay: 5s; animation-duration: 11s; }
	.particle:nth-child(7) { left: 70%; animation-delay: 2.5s; animation-duration: 14s; }
	.particle:nth-child(8) { left: 80%; animation-delay: 1.5s; animation-duration: 12s; }
	.particle:nth-child(9) { left: 90%; animation-delay: 4.5s; animation-duration: 16s; }

	@keyframes float {
		0%, 100% { transform: translateY(100vh) scale(0); opacity: 0; }
		10% { opacity: 1; transform: translateY(90vh) scale(1); }
		90% { opacity: 1; transform: translateY(10vh) scale(1); }
		100% { transform: translateY(0) scale(0); opacity: 0; }
	}

	/* Light mode */
	:global(:root:not(.dark)) .login-page {
		background: linear-gradient(135deg, #f8fafc 0%, #e2e8f0 50%, #cbd5e1 100%);
	}
	:global(:root:not(.dark)) .login-page::before {
		background-image: linear-gradient(rgba(99, 102, 241, 0.08) 1px, transparent 1px),
			linear-gradient(90deg, rgba(99, 102, 241, 0.08) 1px, transparent 1px);
	}
	:global(:root:not(.dark)) .logo-text {
		background: linear-gradient(135deg, #1e293b 0%, #6366f1 100%);
		-webkit-background-clip: text;
		background-clip: text;
	}
</style>
