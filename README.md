# multi-merge
Allows to merge several git branches at once (into octopus merge).

1. Invoke `VCS | Git | Merge Feature Branches`.
2. Select branches to merge, and options to perform after merging.

The action supports **multiple repositories**: for each repository it will merge all specified branches (which exist for this repository).

## Details 

Here is what will be done for each repository:
1. `git checkout -b multi-merge`
2. `git merge feature1 feature2 feature3`
3. Make
4. `git checkout <previous branch>`
5. `git branch -D multi-merge`
6. Quit

## Undo 
The action supports undo: 
if anything fails, it proposes to checkout the branch which you were on before starting the action, 
and remove the temporary `multi-merge` branch.

## Warnings & limitations
* The action doesn't work well with "Make project automatically" option, because step #4 will change the sources and let the make build from incorrect sources.
* The action saves any local changes to the shelf, and restores it after it finishes the work, but this process hasn't been tested well enough yet.
