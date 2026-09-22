# Automated release

LingFrame uses GitHub as its canonical release source. Pull requests run the full quality gate. Developers can manually dispatch the CI workflow with either the `full` or `performance` suite on a development branch. After a merge to `main`, `Main Smoke` runs a short post-merge verification; it does not repeat the full pull-request matrix.

When `Main Smoke` succeeds, the release workflow checks whether the merged commit contains an explicit stable version change and matching release notes. Only then does it create an immutable `V<version>` tag, a GitHub Release, and finally create or advance the `v<version>-release` stable branch. Ordinary feature merges do not create a release. The 0.4 line uses the codename “Hanzhang”, which is retained for 0.4.6.

If a Release, tag, or stable branch is removed accidentally, manually run `Release and Mirror` on `main` and provide the existing stable version. This recovery path revalidates the checked-out source and changelogs before recreating the release artifacts and stable branch. Normal version releases still use only the automatic `Main Smoke` path.

Protect `main` by disallowing direct pushes and requiring the pull-request checks before merge. Required PR checks should include the SB2/SB3 build and test jobs, example integration smoke jobs, and their coverage gates. `Main Smoke` is a post-merge release preflight and does not replace the PR gate.

A release commit must update `lingframe-dependencies/pom.xml`, `lingframe-bom/pom.xml`, both changelogs, and add a matching `V<version>` changelog entry. The workflow rejects mismatched versions, non-stable semantic versions, or missing release notes.

After creating the GitHub Release, the workflow mirrors `main`, the stable release branch, and the release tag to Gitee and GitCode. Configure these SSH secrets in the `mirrors` GitHub Environment:

- `GITEE_SSH_KEY`
- `GITCODE_SSH_KEY`
- `MIRROR_KNOWN_HOSTS`

The mirror repositories should authorize the deployment keys and protect release tags from overwrites. Maven Central remains a separate `-Prelease` operation; this workflow does not upload artifacts.

The stable release branch is created after the Release and does not need to be merged back into `main`. If mirroring fails, rerun the failed matrix task in GitHub Actions without recreating the Release.
