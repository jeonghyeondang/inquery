import tailwindcss from '@tailwindcss/vite';
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

const apiTarget = process.env.API_TARGET || 'http://127.0.0.1:10821';

export default defineConfig({
	plugins: [tailwindcss(), sveltekit()],
	server: {
		port: 3000,
		proxy: {
			'/api': {
				target: apiTarget,
				changeOrigin: true
			}
		}
	},
	ssr: {
		noExternal: ['@icons-pack/svelte-simple-icons']
	}
});
