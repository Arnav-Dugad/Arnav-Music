// Security-rules tests run against the local Firestore emulator (no project, no billing).
import { test, before, after, beforeEach } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, deleteDoc } from 'firebase/firestore';

let env;
const now = Date.now();
const like = (id, extra = {}) => ({ trackId: id, likedAt: now, updatedAt: now, deleted: false, track: { id, title: 'Song', artist: 'Artist', playbackRef: 'abcdefghijk' }, ...extra });
const playlist = (extra = {}) => ({ name: 'Mix', description: '', kind: 'ARNAV', artworkUrl: null, pinned: false, createdAt: now, updatedAt: now, deleted: false, tracks: [], ...extra });

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-arnav-music',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});
after(async () => env && env.cleanup());
beforeEach(async () => env.clearFirestore());

test('owner can write and read their like', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertSucceeds(setDoc(doc(db, 'users/alice/likes/yt:abcdefghijk'), like('yt:abcdefghijk')));
  await assertSucceeds(getDoc(doc(db, 'users/alice/likes/yt:abcdefghijk')));
});

test('other users cannot read or write someone else\'s data', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => setDoc(doc(ctx.firestore(), 'users/alice/likes/yt:x'), like('yt:x')));
  const mallory = env.authenticatedContext('mallory').firestore();
  await assertFails(getDoc(doc(mallory, 'users/alice/likes/yt:x')));
  await assertFails(setDoc(doc(mallory, 'users/alice/likes/yt:y'), like('yt:y')));
  await assertFails(deleteDoc(doc(mallory, 'users/alice/likes/yt:x')));
});

test('unauthenticated access is denied', async () => {
  const anon = env.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(anon, 'users/alice')));
  await assertFails(setDoc(doc(anon, 'users/alice/likes/yt:z'), like('yt:z')));
});

test('rejects unknown fields, wrong types and oversized data', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertFails(setDoc(doc(db, 'users/alice/likes/yt:a'), like('yt:a', { extra: 1 })));
  await assertFails(setDoc(doc(db, 'users/alice/likes/yt:a'), like('yt:a', { deleted: 'no' })));
  await assertFails(setDoc(doc(db, 'users/alice/likes/yt:a'), like('yt:b')));
  await assertFails(setDoc(doc(db, 'users/alice/playlists/p1'), playlist({ name: 'x'.repeat(101) })));
  await assertFails(setDoc(doc(db, 'users/alice/playlists/p1'), playlist({ tracks: new Array(501).fill({}) })));
  await assertFails(setDoc(doc(db, 'users/alice/playlists/p1'), playlist({ kind: 'YOUTUBE' })));
  await assertSucceeds(setDoc(doc(db, 'users/alice/playlists/p1'), playlist()));
});

test('profile document is validated and unmodelled paths are denied', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertSucceeds(setDoc(doc(db, 'users/alice'), { displayName: 'Alice', settings: '{}', updatedAt: now, schema: 1 }));
  await assertFails(setDoc(doc(db, 'users/alice'), { displayName: 'Alice', updatedAt: now, schema: 1, isAdmin: true }));
  await assertFails(setDoc(doc(db, 'users/alice/history/h1'), { any: 1 }));
  await assertFails(setDoc(doc(db, 'global/config'), { any: 1 }));
});
